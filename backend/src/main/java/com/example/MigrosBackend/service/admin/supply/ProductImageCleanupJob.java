package com.example.MigrosBackend.service.admin.supply;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Drives product-image cleanup and retention.
 *
 * <p>Scheduled rather than performed inline so an administrator's image
 * replacement or product deletion is never delayed by, or failed because of,
 * the filesystem. The scan is a bounded page and claiming is atomic, so several
 * instances can run it concurrently without one of them deleting a file another
 * is already deleting - and even if they did, an already-absent file is a
 * success, not a conflict.
 *
 * <h2>Operational tuning</h2>
 *
 * <p>All of these are optional and default through the {@code @Value}
 * fallbacks, so nothing has to be configured for the worker to run:
 *
 * <ul>
 *   <li>{@code product-image.cleanup.scan-ms} / {@code initial-delay-ms} - how
 *       often a page is processed, and how long after startup the first one
 *       runs. The delay is what keeps a restart from racing the instance that
 *       was already running.</li>
 *   <li>{@code product-image.cleanup.lease-seconds} - how long a claimed
 *       obligation stays claimed. A worker killed mid-deletion makes its row
 *       reclaimable after this long, so it trades recovery latency for the
 *       guarantee that a slow deletion is not reclaimed underneath itself.</li>
 *   <li>{@code product-image.cleanup.backoff-base-seconds} /
 *       {@code backoff-max-seconds} - the capped exponential backoff applied to
 *       a deletion that failed.</li>
 *   <li>{@code product-image.cleanup.reference-recheck-seconds} - how long a
 *       still-referenced file waits before being reconsidered.</li>
 *   <li>{@code product-image.cleanup.page-size} - the bounded page size.</li>
 *   <li>{@code product-image.cleanup.retention-days} - how long a
 *       {@code COMPLETED} row is kept for audit. Pending rows are never
 *       deleted by retention.</li>
 *   <li>{@code product-image.cleanup.alert-after-attempts} - a logging
 *       threshold, not a budget. It raises a repeated failure to ERROR for
 *       operator attention; the retry continues either way.</li>
 * </ul>
 *
 * <h2>Failure visibility</h2>
 *
 * <p>A row that keeps failing logs at WARN per attempt and at ERROR once the
 * attempt count passes the alert threshold, carrying the cleanup id and the
 * exception type and never a path. The durable signal to watch is a
 * non-empty, non-decreasing set of {@code PENDING} rows in
 * {@code product_image_cleanup_entity}: every one of them is a file nothing
 * references and that is still on disk.
 *
 * <p>There is no terminal failure state to query for on purpose. A file that
 * could not be deleted is still an unreferenced file, and a status that said
 * "given up" would make the leak permanent while looking like a clean queue.
 */
@Component
public class ProductImageCleanupJob {

    private static final Logger LOG = LoggerFactory.getLogger(ProductImageCleanupJob.class);

    private final ProductImageCleanupWorker worker;
    private final ProductImageCleanupStore store;
    private final Clock clock;
    private final int retentionDays;

    public ProductImageCleanupJob(ProductImageCleanupWorker worker,
                                  ProductImageCleanupStore store,
                                  Clock clock,
                                  @Value("${product-image.cleanup.retention-days:30}") int retentionDays) {
        this.worker = worker;
        this.store = store;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    @Scheduled(
            fixedDelayString = "${product-image.cleanup.scan-ms:60000}",
            initialDelayString = "${product-image.cleanup.initial-delay-ms:60000}")
    public void cleanupDueFiles() {
        try {
            int completed = worker.cleanupDueFiles();
            if (completed > 0) {
                LOG.info("Product image cleanup removed {} obsolete file(s)", completed);
            }
        } catch (RuntimeException ex) {
            // A failing scan must not kill the scheduler: the rows stay claimable
            // and the next tick retries them.
            LOG.error("Product image cleanup scan failed: {}", ex.getClass().getSimpleName());
        }
    }

    @Scheduled(
            fixedDelayString = "${product-image.cleanup.retention-scan-ms:3600000}",
            initialDelayString = "${product-image.cleanup.retention-initial-delay-ms:3600000}")
    public void purgeCompletedWork() {
        try {
            int deleted = store.deleteCompletedBefore(
                    LocalDateTime.now(clock).minusDays(retentionDays));
            if (deleted > 0) {
                LOG.info("Product image cleanup retention deleted {} completed records", deleted);
            }
        } catch (RuntimeException ex) {
            // A failing retention scan must not kill the scheduler; pending
            // records are never touched here, and the next tick retries.
            LOG.error("Product image cleanup retention scan failed: {}", ex.getClass().getSimpleName());
        }
    }
}
