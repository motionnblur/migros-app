package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.repository.product.ProductImageEntityRepository;
import com.example.MigrosBackend.service.global.FileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumer half of the product-image cleanup queue: claims due obligations,
 * deletes the files they name, and records the outcome.
 *
 * <p>Every step is its own commit, and the filesystem call happens in the
 * middle of that with no transaction open. The claim is a single conditional
 * {@code UPDATE}, the delete is a plain filesystem call, and the completion is
 * a fenced {@code UPDATE}. A worker that dies at any point leaves the row
 * claimable again once its bounded lease expires, so a deletion is delayed at
 * worst, never lost - and, because deleting a file that is already gone is a
 * success rather than a duplicate, at-least-once costs nothing here.
 *
 * <p>Deleting files inside a database transaction would hold row locks and
 * connections across a filesystem call that can block on a slow or full disk,
 * for no benefit: nothing in the transaction has to be consistent with the
 * filesystem. Every store transition here therefore runs on the connection
 * with no surrounding transaction, so each one is its own commit and each one
 * ends before the next begins.
 *
 * <p>Cleanup never gives up. A file that cannot be deleted is still a file
 * nobody references, so a failed attempt is rescheduled with capped exponential
 * backoff indefinitely and the attempt count is a logging threshold rather than
 * a budget. The only reason to stop would be to record an obligation as
 * settled while the file is still there, which is exactly the kind of silent
 * leak this queue exists to prevent.
 *
 * <p>Every decision that needs a timestamp reads the clock when it is made,
 * never once per batch. A batch is a sequence of independent deletions, and a
 * lease measured from the instant the scan started is already partly spent by
 * the time the last row in the page is claimed: it lapses while that deletion
 * is still in flight, and the row is reclaimed and deleted again concurrently.
 */
@Service
public class ProductImageCleanupWorker {

    private static final Logger LOG = LoggerFactory.getLogger(ProductImageCleanupWorker.class);

    /** Recorded, and logged, when a file is still referenced by a product. */
    static final String STILL_REFERENCED = "StillReferenced";

    private final ProductImageCleanupStore store;
    private final ProductImageEntityRepository productImageRepository;
    private final FileService fileService;
    private final Clock clock;
    private final long leaseSeconds;
    private final long backoffBaseSeconds;
    private final long backoffMaxSeconds;
    private final long referenceRecheckSeconds;
    private final int escalationThreshold;
    private final int pageSize;

    public ProductImageCleanupWorker(
            ProductImageCleanupStore store,
            ProductImageEntityRepository productImageRepository,
            FileService fileService,
            Clock clock,
            @Value("${product-image.cleanup.lease-seconds:120}") long leaseSeconds,
            @Value("${product-image.cleanup.alert-after-attempts:8}") int escalationThreshold,
            @Value("${product-image.cleanup.backoff-base-seconds:60}") long backoffBaseSeconds,
            @Value("${product-image.cleanup.backoff-max-seconds:3600}") long backoffMaxSeconds,
            @Value("${product-image.cleanup.reference-recheck-seconds:300}") long referenceRecheckSeconds,
            @Value("${product-image.cleanup.page-size:20}") int pageSize) {
        this.store = store;
        this.productImageRepository = productImageRepository;
        this.fileService = fileService;
        this.clock = clock;
        this.leaseSeconds = Math.max(1, leaseSeconds);
        this.escalationThreshold = Math.max(1, escalationThreshold);
        this.backoffBaseSeconds = Math.max(1, backoffBaseSeconds);
        this.backoffMaxSeconds = Math.max(backoffBaseSeconds, backoffMaxSeconds);
        this.referenceRecheckSeconds = Math.max(1, referenceRecheckSeconds);
        this.pageSize = Math.max(1, pageSize);
    }

    /**
     * Processes one bounded page of due obligations. Returns how many this call
     * durably completed.
     */
    public int cleanupDueFiles() {
        // Only the scan is batched; each claim below is timed on its own.
        List<String> dueCleanupIds = store.findDue(pageSize, LocalDateTime.now(clock));

        int completed = 0;
        for (String cleanupId : dueCleanupIds) {
            if (cleanupOnce(cleanupId)) {
                completed++;
            }
        }
        return completed;
    }

    /**
     * Claims, deletes, and records one file.
     *
     * @return {@code true} only when this worker durably completed the obligation
     */
    public boolean cleanupOnce(String cleanupId) {
        // Timed here, not once per batch: an earlier deletion in the page can
        // push this claim most of a lease into the past, and a lease that is
        // already spent when taken is a lease another worker may reclaim while
        // this deletion is still in flight.
        LocalDateTime claimedAt = LocalDateTime.now(clock);

        // A fresh token per attempt: it is the fencing token that proves to the
        // completion update that this worker still owns the row.
        String leaseOwner = UUID.randomUUID().toString();
        Optional<ProductImageCleanupStore.ClaimedCleanup> claimed =
                store.tryClaim(cleanupId, leaseOwner, claimedAt, leaseSeconds);
        if (claimed.isEmpty()) {
            // Another worker holds a valid lease, or the backoff has not elapsed.
            return false;
        }

        ProductImageCleanupStore.ClaimedCleanup cleanup = claimed.get();

        if (isStillReferenced(cleanup.fileIdentity())) {
            defer(cleanup, leaseOwner);
            return false;
        }

        FileService.DeletionOutcome outcome;
        try {
            // No transaction is open here, and none is wanted: see the class
            // comment on why filesystem I/O must not sit inside one.
            outcome = fileService.deleteStoredFile(cleanup.fileIdentity());
        } catch (RuntimeException ex) {
            // deleteStoredFile already converts an IOException into FAILED, so
            // anything arriving here is a defect rather than a filesystem
            // condition. Retrying is still the right answer; it just must not
            // take the scheduler down with it.
            recordFailure(cleanup, leaseOwner, ex);
            return false;
        }

        switch (outcome) {
            case DELETED, ALREADY_ABSENT -> {
                ProductImageCleanupStore.Transition transition =
                        store.markCompleted(cleanup.cleanupId(), leaseOwner, LocalDateTime.now(clock));
                if (transition != ProductImageCleanupStore.Transition.APPLIED) {
                    // The lease was reclaimed while the delete was in flight, so
                    // the file may be deleted twice. That is harmless - an
                    // already-absent file is a success - but it must never be
                    // reported as this worker's completion.
                    LOG.warn("Product image cleanup {} deleted its file but its lease expired "
                            + "before completion", cleanup.cleanupId());
                }
                return transition == ProductImageCleanupStore.Transition.APPLIED;
            }
            case REFUSED -> {
                // The identity is not a confined file name. It can never be
                // deleted, and nothing outside the upload directory will be
                // touched. Retried rather than abandoned so the condition stays
                // visible instead of becoming a permanent, invisible row.
                recordFailure(cleanup, leaseOwner,
                        new IllegalStateException("RefusingToDeleteOutsideUploadDirectory"));
                return false;
            }
            default -> {
                recordFailure(cleanup, leaseOwner, new IllegalStateException("FilesystemDeleteFailed"));
                return false;
            }
        }
    }

    /**
     * Whether any current image reference resolves to this canonical file
     * identity.
     *
     * <p>The check has to be conservative in one direction only: a false
     * negative deletes a file a live product is serving, while a false positive
     * merely postpones a deletion until the next scan. So the database is asked
     * for a superset of candidate spellings and every candidate is then put
     * through the same canonicalization {@code FileService} applies when it
     * serves the file, which is what makes {@code C:\dir\image_x.png},
     * {@code /var/uploads/image_x.png} and {@code image_x.png} one file and
     * keeps {@code other_image_x.png} from being mistaken for one.
     *
     * <p>This is sound rather than merely cautious because of an invariant the
     * upload path already guarantees: a new upload is always written under a
     * fresh UUID name, so no concurrent request can attach a reference to a file
     * that has just been made obsolete. The only references a live file can
     * have are the ones already in the table, and this reads all of them.
     */
    boolean isStillReferenced(String fileIdentity) {
        for (String candidate : productImageRepository.findImagePathsPossiblyReferencing(fileIdentity)) {
            if (fileIdentity.equals(fileService.canonicalFileIdentity(candidate))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts a still-referenced file back on the queue without treating it as a
     * failure.
     *
     * <p>A shared legacy file is a normal state, not an incident: several
     * products can point at one file, and the obligation only becomes actionable
     * when the last of them stops referencing it. So the re-check uses a fixed
     * interval instead of the failure backoff, and the row keeps its place in
     * the queue rather than being dropped - dropping it would abandon the
     * cleanup the moment the file happened to still be shared.
     */
    private void defer(ProductImageCleanupStore.ClaimedCleanup cleanup, String leaseOwner) {
        LocalDateTime recheckAt = LocalDateTime.now(clock).plusSeconds(referenceRecheckSeconds);
        store.scheduleRetry(cleanup.cleanupId(), leaseOwner, STILL_REFERENCED, recheckAt);
        LOG.info("Deferring product image cleanup {}: the file is still referenced by a product",
                cleanup.cleanupId());
    }

    /**
     * Reschedules a failed attempt, measured from the moment it actually failed.
     *
     * <p>Backoff has to start at the failure, not at the claim or at the start
     * of the batch. Measured from an earlier timestamp it is already partly
     * spent when written, so a slow failure is retried immediately - a tight
     * retry loop against a disk that is already refusing.
     */
    private void recordFailure(ProductImageCleanupStore.ClaimedCleanup cleanup, String leaseOwner,
                               RuntimeException failure) {
        LocalDateTime failedAt = LocalDateTime.now(clock);
        String errorCode = ProductImageCleanupStore.sanitizeError(failure);
        LocalDateTime nextAttemptAt = failedAt.plusSeconds(backoffSeconds(cleanup.attemptCount()));
        store.scheduleRetry(cleanup.cleanupId(), leaseOwner, errorCode, nextAttemptAt);

        // Cleanup ids and error types only. A file path is the upload layout,
        // and the canonical identity is deliberately absent from the message
        // for the same reason: it is never logged here.
        if (cleanup.attemptCount() >= escalationThreshold) {
            LOG.error("Product image cleanup {} has failed {} times in a row ({}); still retrying at {}, "
                            + "the file is unreferenced and cannot be removed",
                    cleanup.cleanupId(), cleanup.attemptCount(), errorCode, nextAttemptAt);
            return;
        }
        LOG.warn("Product image cleanup {} failed ({}), retrying at {}",
                cleanup.cleanupId(), errorCode, nextAttemptAt);
    }

    /** Exponential backoff, capped, with no overflow on a long-lived leak. */
    long backoffSeconds(int attemptCount) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 32));
        long delay = backoffBaseSeconds;
        for (int i = 0; i < exponent; i++) {
            if (delay >= backoffMaxSeconds) {
                return backoffMaxSeconds;
            }
            delay <<= 1;
        }
        return Math.min(delay, backoffMaxSeconds);
    }
}
