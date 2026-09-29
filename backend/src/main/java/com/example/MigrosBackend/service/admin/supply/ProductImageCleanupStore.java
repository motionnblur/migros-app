package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.entity.product.ProductImageCleanupStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable queue of product image files that are no longer referenced and still
 * have to be removed from the upload directory.
 *
 * <p>Enqueue, claim, and completion are deliberately separate commits, for the
 * same reason the support outbox's are: a crash between any two leaves the row
 * in a reclaimable state instead of losing the only record that the file is
 * obsolete. Nothing here is ever inferred from the mere existence of a row.
 *
 * <ul>
 *   <li>{@link #enqueue} runs inside the caller's transaction, so the product
 *       change and the obligation it creates commit or roll back together.
 *       There is no window in which a file is unreferenced and nothing records
 *       that fact.</li>
 *   <li>{@link #tryClaim} is one conditional {@code UPDATE}, so only one worker
 *       can hold a valid lease and an expired lease is reclaimable.</li>
 *   <li>Every worker transition is fenced on the lease token: a worker that
 *       lost its lease can never mark a row completed or reschedule it.</li>
 * </ul>
 *
 * <p>This is not the support outbox and borrows none of its delivery
 * contract. There is no payload, because there is nothing to send: the work is
 * a local, idempotent filesystem effect. There is no ordering requirement,
 * because two unrelated files have no conversation to reorder. There is no
 * receiver-side deduplication, because deleting a file that is already gone is
 * a success, not a duplicate. What is borrowed is only the lease/claim/retry
 * <em>shape</em>, which is the minimum needed to let several instances and
 * restarts share one queue safely.
 *
 * <p>The stored {@code file_identity} is a canonical bare file name, never a
 * stored path. A stored value may be a legacy absolute path or use either
 * separator; storing the name rather than the spelling is what lets the worker
 * compare it against re-canonicalized references later.
 */
@Component
public class ProductImageCleanupStore {

    /**
     * Outcome of a worker-owned transition. The lease token acts as a fencing
     * token: a zero-row conditional update is reported as {@code STALE_CLAIM}
     * and never as success, so a worker whose lease was reclaimed cannot close
     * or reschedule a row another worker now owns.
     */
    public enum Transition {
        APPLIED,
        STALE_CLAIM
    }

    /**
     * A claimed row together with everything the worker needs.
     *
     * @param attemptCount already incremented for this attempt
     */
    public record ClaimedCleanup(String cleanupId, String fileIdentity,
                                 int attemptCount, String leaseOwner) {
    }

    /**
     * The status tokens embedded in the SQL below. They are derived from
     * {@link ProductImageCleanupStatus} so a renamed enum constant becomes a
     * compile error here rather than a claim scan that silently matches nothing.
     */
    static final String STATUS_PENDING = ProductImageCleanupStatus.PENDING.name();
    static final String STATUS_PROCESSING = ProductImageCleanupStatus.PROCESSING.name();
    static final String STATUS_COMPLETED = ProductImageCleanupStatus.COMPLETED.name();

    private static final RowMapper<ClaimedCleanup> CLAIMED_CLEANUP_MAPPER = new RowMapper<>() {
        @Override
        public ClaimedCleanup mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ClaimedCleanup(
                    rs.getString("cleanup_id"),
                    rs.getString("file_identity"),
                    rs.getInt("attempt_count"),
                    rs.getString("lease_owner"));
        }
    };

    private final JdbcTemplate jdbcTemplate;

    public ProductImageCleanupStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Records that the current transaction has stopped referencing a file.
     *
     * <p>Must be called from inside that transaction: that is the whole point of
     * the queue. Called on its own it would produce a row promising a deletion
     * for a change that later rolled back, and the worker would delete a file a
     * live product is still serving.
     *
     * <p>Outstanding work is deduplicated by {@code file_identity} through a
     * partial unique index, so two products that shared one legacy file - or a
     * replacement and a deletion that both obsolete the same name - produce one
     * row rather than two workers racing to delete the same file.
     *
     * <p>The conflict target repeats the index predicate because that is how
     * PostgreSQL infers a <em>partial</em> unique index; a bare
     * {@code ON CONFLICT (file_identity)} would not resolve to it.
     */
    public void enqueue(String fileIdentity, LocalDateTime now) {
        if (fileIdentity == null || fileIdentity.isBlank()) {
            throw new IllegalArgumentException("cleanup work requires a canonical file identity");
        }
        Timestamp nowTs = Timestamp.valueOf(now);
        jdbcTemplate.update(
                "INSERT INTO product_image_cleanup_entity "
                        + "(cleanup_id, file_identity, status, attempt_count, next_attempt_at, created_at) "
                        + "VALUES (?, ?, '" + STATUS_PENDING + "', 0, ?, ?) "
                        + "ON CONFLICT (file_identity) WHERE status IN ('"
                        + STATUS_PENDING + "', '" + STATUS_PROCESSING + "') DO NOTHING",
                UUID.randomUUID().toString(), fileIdentity, nowTs, nowTs);
    }

    /**
     * Atomically claims one obligation for a single worker. Succeeds for
     * {@code PENDING} rows whose backoff elapsed and for {@code PROCESSING} rows
     * whose lease expired (a worker that died mid-deletion). Returns empty when
     * another worker holds a valid lease.
     *
     * <p>The caller generates the {@code leaseOwner} token and must present it to
     * every worker transition afterwards.
     */
    public Optional<ClaimedCleanup> tryClaim(String cleanupId, String leaseOwner,
                                             LocalDateTime now, long leaseSeconds) {
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("claim requires a non-blank lease owner token");
        }
        Timestamp nowTs = Timestamp.valueOf(now);
        List<ClaimedCleanup> claimed = jdbcTemplate.query(
                "UPDATE product_image_cleanup_entity SET status = '" + STATUS_PROCESSING + "', "
                        + "lease_owner = ?, lease_expires_at = ?, attempt_count = attempt_count + 1 "
                        + "WHERE cleanup_id = ? "
                        + "AND (status = '" + STATUS_PENDING + "' "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
                        + "OR (status = '" + STATUS_PROCESSING + "' "
                        + "AND (lease_expires_at IS NULL OR lease_expires_at <= ?))) "
                        + "RETURNING cleanup_id, file_identity, attempt_count, lease_owner",
                CLAIMED_CLEANUP_MAPPER, leaseOwner,
                Timestamp.valueOf(now.plusSeconds(leaseSeconds)), cleanupId, nowTs, nowTs);
        return claimed.stream().findFirst();
    }

    /**
     * Marks an obligation durably complete: the file is gone, or was already
     * gone. Fenced on the lease token, so a worker that lost its lease changes
     * nothing and receives {@code STALE_CLAIM}.
     */
    public Transition markCompleted(String cleanupId, String leaseOwner, LocalDateTime completedAt) {
        if (leaseOwner == null || leaseOwner.isBlank()) {
            return Transition.STALE_CLAIM;
        }
        return jdbcTemplate.update(
                "UPDATE product_image_cleanup_entity SET status = '" + STATUS_COMPLETED + "', "
                        + "completed_at = ?, lease_owner = NULL, lease_expires_at = NULL, last_error = NULL "
                        + "WHERE cleanup_id = ? AND status = '" + STATUS_PROCESSING + "' AND lease_owner = ?",
                Timestamp.valueOf(completedAt), cleanupId, leaseOwner) == 1
                ? Transition.APPLIED
                : Transition.STALE_CLAIM;
    }

    /**
     * Parks a failed or deferred attempt for a bounded retry. Fenced on the
     * lease token like every other worker transition.
     *
     * <p>There is no terminal counterpart, and that is deliberate. The file is
     * still unreferenced; the only alternatives to retrying are abandoning a
     * leak forever or hiding it, so a failure is rescheduled with capped
     * exponential backoff indefinitely and the attempt count is only a logging
     * threshold.
     */
    public Transition scheduleRetry(String cleanupId, String leaseOwner, String errorCode,
                                    LocalDateTime nextAttemptAt) {
        if (leaseOwner == null || leaseOwner.isBlank()) {
            return Transition.STALE_CLAIM;
        }
        return jdbcTemplate.update(
                "UPDATE product_image_cleanup_entity SET status = '" + STATUS_PENDING + "', "
                        + "last_error = ?, next_attempt_at = ?, lease_owner = NULL, lease_expires_at = NULL "
                        + "WHERE cleanup_id = ? AND status = '" + STATUS_PROCESSING + "' AND lease_owner = ?",
                errorCode, Timestamp.valueOf(nextAttemptAt), cleanupId, leaseOwner) == 1
                ? Transition.APPLIED
                : Transition.STALE_CLAIM;
    }

    /**
     * Bounded page of due obligations, oldest first.
     *
     * <p>There is no head-of-line predicate, unlike the support outbox: two
     * different files are independent, so a file whose deletion keeps failing
     * must not hold back an unrelated one. The only reason one row can stop
     * another is that they are the same file, and the partial unique index
     * already collapsed those into a single row.
     */
    public List<String> findDue(int limit, LocalDateTime now) {
        Timestamp nowTs = Timestamp.valueOf(now);
        return jdbcTemplate.queryForList(
                "SELECT cleanup_id FROM product_image_cleanup_entity "
                        + "WHERE (status = '" + STATUS_PENDING + "' "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?)) "
                        + "OR (status = '" + STATUS_PROCESSING + "' "
                        + "AND (lease_expires_at IS NULL OR lease_expires_at <= ?)) "
                        + "ORDER BY sequence_no ASC LIMIT ?",
                String.class, nowTs, nowTs, limit);
    }

    /**
     * Retention cleanup. Deletes only {@code COMPLETED} obligations finished
     * before the cutoff. Pending work is never removed: the file is still
     * unreferenced and still there.
     */
    public int deleteCompletedBefore(LocalDateTime cutoff) {
        return jdbcTemplate.update(
                "DELETE FROM product_image_cleanup_entity "
                        + "WHERE status = '" + STATUS_COMPLETED + "' "
                        + "AND completed_at IS NOT NULL AND completed_at < ?",
                Timestamp.valueOf(cutoff));
    }

    /**
     * Truncates a failure to what is safe to persist and to log: the exception
     * type only. A filesystem exception message can carry the absolute path of
     * the file that failed, which is exactly the upload layout an operator log
     * should not be publishing.
     */
    static String sanitizeError(Throwable failure) {
        String type = failure == null || failure.getClass() == null
                ? "UnknownError"
                : failure.getClass().getSimpleName();
        if (type.isBlank()) {
            type = "UnknownError";
        }
        return type.length() > 64 ? type.substring(0, 64) : type;
    }
}
