package com.example.MigrosBackend.service.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Durable outbox for events owed to the external support service.
 *
 * <p>Enqueue, claim, and completion are deliberately separate commits, for the
 * same reason the Stripe webhook inbox is: a crash between any two leaves the
 * row in a reclaimable state instead of losing the event. Nothing here is ever
 * inferred from the mere existence of a row.
 *
 * <ul>
 *   <li>{@link #enqueue} runs inside the caller's transaction, so a chat write
 *       and the event it owes commit or roll back together. There is no window
 *       where the message exists but the notification does not.</li>
 *   <li>{@link #tryClaim} is one conditional {@code UPDATE}, so only one worker
 *       can hold a valid lease and an expired lease is reclaimable.</li>
 *   <li>Every worker transition is fenced on the lease token: a worker that
 *       lost its lease can never mark a row delivered or reschedule it.</li>
 * </ul>
 *
 * <p>The stored payload is the exact JSON body to post and may contain customer
 * message text. It is never logged here; only event ids, types, and truncated
 * error codes appear in logs.
 */
@Component
public class SupportOutboxStore {

    /**
     * Outcome of a worker-owned transition. The lease token acts as a fencing
     * token: a zero-row conditional update is reported as {@code STALE_CLAIM}
     * and never as success, so an expired worker cannot overwrite a newer
     * claim.
     */
    public enum Transition {
        APPLIED,
        STALE_CLAIM
    }

    /**
     * A claimed row together with everything needed to deliver it.
     *
     * @param attemptCount already incremented for this delivery attempt
     */
    public record ClaimedEvent(String eventId, String eventType, String userMail,
                               String payload, int attemptCount, String leaseOwner) {
    }

    private static final RowMapper<ClaimedEvent> CLAIMED_EVENT_MAPPER = new RowMapper<>() {
        @Override
        public ClaimedEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ClaimedEvent(
                    rs.getString("event_id"),
                    rs.getString("event_type"),
                    rs.getString("user_mail"),
                    rs.getString("payload"),
                    rs.getInt("attempt_count"),
                    rs.getString("lease_owner"));
        }
    };

    /** {@code pg_advisory_xact_lock} returns void, so there is no row to map. */
    private static final RowCallbackHandler IGNORE_ROW = resultSet -> {
    };

    private final JdbcTemplate jdbcTemplate;

    public SupportOutboxStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Records an event that the current transaction owes to the support
     * service. Must be called from inside that transaction: that is the whole
     * point of the outbox.
     *
     * <p>{@code eventId} is also the {@code eventId} field inside the payload
     * and is never regenerated, so a retry after a failure is byte-identical
     * and the receiver can deduplicate it.
     *
     * <p>The row is inserted under a per-customer transaction advisory lock, so
     * that {@code sequence_no} order is <em>commit</em> order for one customer.
     * A sequence is handed out at insert time, which is not the same thing: two
     * concurrent chat writes for the same customer can take sequence numbers in
     * one order and commit in the other, and the later-committing row with the
     * smaller number would then be delivered after it. Serializing the insert
     * behind a lock held until commit removes that window, and it is scoped per
     * customer so unrelated conversations never wait on each other.
     */
    public void enqueue(String eventId, String eventType, String userMail,
                        String payload, LocalDateTime now) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("an outbox event requires a stable event id");
        }
        lockCustomerQueue(userMail);
        jdbcTemplate.update(
                "INSERT INTO support_outbox_entity "
                        + "(event_id, event_type, user_mail, payload, status, attempt_count, "
                        + "next_attempt_at, created_at) "
                        + "VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?)",
                eventId, eventType, userMail, payload,
                Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    /**
     * Blocks until this transaction owns {@code userMail}'s queue, and keeps
     * owning it until it commits or rolls back.
     *
     * <p>{@code pg_advisory_xact_lock} is released by the transaction boundary
     * itself, so there is no lease to expire and no way to end up holding a lock
     * for a queue that is not ours. The key is derived here rather than in SQL
     * with {@code hashtext()} so it is a stable, documented function of the
     * mailbox rather than an internal one whose definition may change between
     * PostgreSQL versions.
     */
    private void lockCustomerQueue(String userMail) {
        if (userMail == null || userMail.isBlank()) {
            throw new IllegalArgumentException("an outbox event requires a customer mailbox");
        }
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(?)", IGNORE_ROW, customerQueueLockKey(userMail));
    }

    /**
     * Deterministic 64-bit advisory-lock key for one customer's queue.
     *
     * <p>Exposed for tests that need to hold the lock from an independent
     * connection. Must stay stable across processes and restarts, so it is a
     * truncated SHA-256 of the mailbox rather than {@link String#hashCode()}.
     */
    static long customerQueueLockKey(String userMail) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(userMail.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required to derive support outbox lock keys", ex);
        }
        long key = 0L;
        for (int i = 0; i < Long.BYTES; i++) {
            key = (key << 8) | (digest[i] & 0xFFL);
        }
        return key;
    }

    /**
     * Atomically claims one event for a single worker. Succeeds for
     * {@code PENDING} rows whose backoff elapsed and for {@code PROCESSING} rows
     * whose lease expired (a worker that died mid-delivery). Returns empty when
     * another worker holds a valid lease.
     *
     * <p>The caller generates the {@code leaseOwner} token and must present it
     * to every worker transition afterwards.
     */
    public Optional<ClaimedEvent> tryClaim(String eventId, String leaseOwner,
                                           LocalDateTime now, long leaseSeconds) {
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("claim requires a non-blank lease owner token");
        }
        Timestamp nowTs = Timestamp.valueOf(now);
        List<ClaimedEvent> claimed = jdbcTemplate.query(
                "UPDATE support_outbox_entity SET status = 'PROCESSING', lease_owner = ?, "
                        + "lease_expires_at = ?, attempt_count = attempt_count + 1 "
                        + "WHERE event_id = ? "
                        + "AND (status = 'PENDING' "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
                        + "OR (status = 'PROCESSING' "
                        + "AND (lease_expires_at IS NULL OR lease_expires_at <= ?))) "
                        + "RETURNING event_id, event_type, user_mail, payload, attempt_count, lease_owner",
                CLAIMED_EVENT_MAPPER, leaseOwner,
                Timestamp.valueOf(now.plusSeconds(leaseSeconds)), eventId, nowTs, nowTs);
        return claimed.stream().findFirst();
    }

    /**
     * Marks an event durably complete. Fenced on the lease token: a worker
     * whose lease was reclaimed changes nothing and receives
     * {@code STALE_CLAIM}, so a late success can never overwrite a newer claim
     * or a terminal failure decision.
     */
    public Transition markDelivered(String eventId, String leaseOwner, LocalDateTime deliveredAt) {
        if (leaseOwner == null || leaseOwner.isBlank()) {
            return Transition.STALE_CLAIM;
        }
        return jdbcTemplate.update(
                "UPDATE support_outbox_entity SET status = 'DELIVERED', delivered_at = ?, "
                        + "lease_owner = NULL, lease_expires_at = NULL, last_error = NULL "
                        + "WHERE event_id = ? AND status = 'PROCESSING' AND lease_owner = ?",
                Timestamp.valueOf(deliveredAt), eventId, leaseOwner) == 1
                ? Transition.APPLIED
                : Transition.STALE_CLAIM;
    }

    /**
     * Parks a failed attempt for a bounded retry with the given backoff. Fenced
     * on the lease token like every other worker transition.
     *
     * <p>There is no terminal counterpart to this method. A support event is
     * owed for as long as the receiver has not acknowledged it, and stopping
     * would both lose the event and let the customer's later events overtake it:
     * {@link #findDue} only holds back a queue behind rows that are still
     * {@code PENDING} or {@code PROCESSING}, so a permanently failed row would
     * quietly unblock the queue and reorder the conversation.
     */
    public Transition scheduleRetry(String eventId, String leaseOwner, String errorCode,
                                    LocalDateTime nextAttemptAt) {
        if (leaseOwner == null || leaseOwner.isBlank()) {
            return Transition.STALE_CLAIM;
        }
        return jdbcTemplate.update(
                "UPDATE support_outbox_entity SET status = 'PENDING', last_error = ?, "
                        + "next_attempt_at = ?, lease_owner = NULL, lease_expires_at = NULL "
                        + "WHERE event_id = ? AND status = 'PROCESSING' AND lease_owner = ?",
                errorCode, Timestamp.valueOf(nextAttemptAt), eventId, leaseOwner) == 1
                ? Transition.APPLIED
                : Transition.STALE_CLAIM;
    }

    /**
     * Bounded page of deliverable events, oldest first.
     *
     * <p>A row is only offered when no <em>earlier</em> row for the same
     * customer is still undelivered. That is what makes a retry of event 1 not
     * leapfrog a later event for the same conversation: the customer's events
     * reach the support service in the order the backend committed them, even
     * when an earlier delivery failed and is waiting out its backoff. Different
     * customers are unaffected and stay fully parallel.
     *
     * <p>"Earlier" means a lower {@code sequence_no}, and {@link #enqueue}
     * serializes those per customer under a transaction advisory lock so that
     * lower number really does mean earlier commit. Without that serialization
     * this predicate is unsound: a row that is not yet committed is invisible
     * here, so a later event could be claimed and delivered while an earlier one
     * is still in flight.
     *
     * <p>Retries of different customers are therefore independent; only one
     * customer can be blocked at a time.
     */
    public List<String> findDue(int limit, LocalDateTime now) {
        Timestamp nowTs = Timestamp.valueOf(now);
        return jdbcTemplate.queryForList(
                "SELECT o.event_id FROM support_outbox_entity o "
                        + "WHERE ((o.status = 'PENDING' "
                        + "AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= ?)) "
                        + "OR (o.status = 'PROCESSING' "
                        + "AND (o.lease_expires_at IS NULL OR o.lease_expires_at <= ?))) "
                        + "AND NOT EXISTS (SELECT 1 FROM support_outbox_entity p "
                        + "WHERE p.user_mail = o.user_mail "
                        + "AND p.sequence_no < o.sequence_no "
                        + "AND p.status IN ('PENDING', 'PROCESSING')) "
                        + "ORDER BY o.sequence_no ASC LIMIT ?",
                String.class, nowTs, nowTs, limit);
    }

    /**
     * Retention cleanup. Deletes only {@code DELIVERED} events completed
     * before the cutoff. Undelivered events are never removed: they are still
     * owed.
     */
    public int deleteDeliveredBefore(LocalDateTime cutoff) {
        return jdbcTemplate.update(
                "DELETE FROM support_outbox_entity "
                        + "WHERE status = 'DELIVERED' AND delivered_at IS NOT NULL AND delivered_at < ?",
                Timestamp.valueOf(cutoff));
    }

    /**
     * Truncates a delivery error to what is safe to persist. Only the exception
     * type is kept: an HTTP error body can echo back request content, and the
     * payload is customer data.
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
