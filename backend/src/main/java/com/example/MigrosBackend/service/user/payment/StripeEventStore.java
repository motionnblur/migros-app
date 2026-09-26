package com.example.MigrosBackend.service.user.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Durable Stripe webhook inbox. Receipt, claiming, and completion are separate
 * commits on purpose: a crash between any two leaves the row in a reclaimable
 * state instead of silently discarding the event.
 *
 * <ul>
 *   <li>Receipt inserts {@code RECEIVED} atomically ({@code ON CONFLICT DO
 *   NOTHING}); concurrent duplicates converge on one row.</li>
 *   <li>Claiming is a single conditional {@code UPDATE} so only one worker can
 *   hold a valid lease; an expired lease is reclaimable.</li>
 *   <li>Only {@code PROCESSED} means complete. Redelivery checks the stored
 *   status, never mere row existence.</li>
 * </ul>
 *
 * <p>The stored payload is the exact raw body that passed signature
 * verification. It is never logged here; only event ids, types, and sanitized
 * error codes appear in logs.
 */
@Component
public class StripeEventStore {

    public enum ReceiveOutcome {
        /** First verified delivery; the row was inserted as RECEIVED. */
        RECEIVED_NEW,
        /** Known id with identical content that still needs processing. */
        NEEDS_PROCESSING,
        /** Known id whose effects are already durably complete. */
        ALREADY_PROCESSED,
        /** Same id arrived with a different type or payload hash. */
        CONFLICT
    }

    public record StoredEvent(String eventId, String eventType, String payload,
                              String payloadHash, String status, int attemptCount) {
    }

    private static final RowMapper<StoredEvent> STORED_EVENT_MAPPER = new RowMapper<>() {
        @Override
        public StoredEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new StoredEvent(
                    rs.getString("event_id"),
                    rs.getString("event_type"),
                    rs.getString("payload"),
                    rs.getString("payload_hash"),
                    rs.getString("status"),
                    rs.getInt("attempt_count"));
        }
    };

    private final JdbcTemplate jdbcTemplate;

    public StripeEventStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Atomically records a verified delivery. Must only be called after the
     * Stripe signature over the exact raw body has been verified.
     */
    public ReceiveOutcome receive(String eventId, String eventType, String payload,
                                  String payloadHash, LocalDateTime receivedAt) {
        int rows = jdbcTemplate.update(
                "INSERT INTO stripe_event_entity "
                        + "(event_id, event_type, payload, payload_hash, status, "
                        + "attempt_count, received_at, next_attempt_at) "
                        + "VALUES (?, ?, ?, ?, 'RECEIVED', 0, ?, ?) "
                        + "ON CONFLICT (event_id) DO NOTHING",
                eventId, eventType, payload, payloadHash,
                Timestamp.valueOf(receivedAt), Timestamp.valueOf(receivedAt));
        if (rows == 1) {
            return ReceiveOutcome.RECEIVED_NEW;
        }
        StoredEvent existing = findStored(eventId).orElse(null);
        if (existing == null) {
            // Lost a race with a concurrent delete; let the caller retry.
            return ReceiveOutcome.NEEDS_PROCESSING;
        }
        if (!existing.eventType().equals(eventType)
                || !existing.payloadHash().equals(payloadHash)) {
            return ReceiveOutcome.CONFLICT;
        }
        if ("PROCESSED".equals(existing.status())) {
            return ReceiveOutcome.ALREADY_PROCESSED;
        }
        return ReceiveOutcome.NEEDS_PROCESSING;
    }

    /**
     * Atomically claims a reclaimable event for one worker. Succeeds for
     * {@code RECEIVED} rows, {@code PROCESSING} rows whose lease expired, and
     * {@code FAILED} rows whose next attempt is due. Returns the stored event
     * (with the incremented attempt count) or empty when another worker holds
     * a valid lease.
     */
    public Optional<StoredEvent> tryClaim(String eventId, String leaseOwner,
                                          LocalDateTime now, long leaseSeconds) {
        Timestamp leaseExpires = Timestamp.valueOf(now.plusSeconds(leaseSeconds));
        Timestamp nowTs = Timestamp.valueOf(now);
        List<StoredEvent> claimed = jdbcTemplate.query(
                "UPDATE stripe_event_entity SET status = 'PROCESSING', lease_owner = ?, "
                        + "lease_expires_at = ?, processing_started_at = ?, "
                        + "attempt_count = attempt_count + 1 "
                        + "WHERE event_id = ? "
                        + "AND (status = 'RECEIVED' "
                        + "OR (status = 'PROCESSING' "
                        + "AND (lease_expires_at IS NULL OR lease_expires_at <= ?)) "
                        + "OR (status = 'FAILED' "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?))) "
                        + "RETURNING event_id, event_type, payload, payload_hash, status, attempt_count",
                STORED_EVENT_MAPPER, leaseOwner, leaseExpires, nowTs, eventId, nowTs, nowTs);
        return claimed.stream().findFirst();
    }

    public Optional<StoredEvent> findStored(String eventId) {
        List<StoredEvent> rows = jdbcTemplate.query(
                "SELECT event_id, event_type, payload, payload_hash, status, attempt_count "
                        + "FROM stripe_event_entity WHERE event_id = ?",
                STORED_EVENT_MAPPER, eventId);
        return rows.stream().findFirst();
    }

    /**
     * Marks the event durably complete. Only a {@code PROCESSING} row can
     * become {@code PROCESSED} so a late completion can never clobber a
     * manual-review decision.
     */
    public boolean markProcessed(String eventId, LocalDateTime processedAt) {
        return jdbcTemplate.update(
                "UPDATE stripe_event_entity SET status = 'PROCESSED', processed_at = ?, "
                        + "lease_owner = NULL, lease_expires_at = NULL "
                        + "WHERE event_id = ? AND status = 'PROCESSING'",
                Timestamp.valueOf(processedAt), eventId) == 1;
    }

    /**
     * Parks the event for a bounded retry. Only a {@code PROCESSING} row can
     * become {@code FAILED}.
     */
    public boolean markFailed(String eventId, String errorCode, LocalDateTime nextAttemptAt) {
        return jdbcTemplate.update(
                "UPDATE stripe_event_entity SET status = 'FAILED', last_error = ?, "
                        + "next_attempt_at = ?, lease_owner = NULL, lease_expires_at = NULL "
                        + "WHERE event_id = ? AND status = 'PROCESSING'",
                errorCode, Timestamp.valueOf(nextAttemptAt), eventId) == 1;
    }

    /**
     * Fails the event closed for operator review. Unconditional so an
     * event-id collision stays visible even if it races a completion.
     */
    public void markManualReview(String eventId, String reason) {
        jdbcTemplate.update(
                "UPDATE stripe_event_entity SET status = 'MANUAL_REVIEW', last_error = ?, "
                        + "lease_owner = NULL, lease_expires_at = NULL, next_attempt_at = NULL "
                        + "WHERE event_id = ?",
                reason, eventId);
    }

    /**
     * Bounded page of events eligible for work, oldest first: unclaimed
     * receipts, claims whose lease expired, and retries whose backoff elapsed.
     */
    public List<String> findDue(int limit, LocalDateTime now) {
        Timestamp nowTs = Timestamp.valueOf(now);
        return jdbcTemplate.queryForList(
                "SELECT event_id FROM stripe_event_entity "
                        + "WHERE status = 'RECEIVED' "
                        + "OR (status = 'PROCESSING' "
                        + "AND (lease_expires_at IS NULL OR lease_expires_at <= ?)) "
                        + "OR (status = 'FAILED' "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?)) "
                        + "ORDER BY received_at ASC LIMIT ?",
                String.class, nowTs, nowTs, limit);
    }

    /**
     * Retention cleanup. Deletes only {@code PROCESSED} events completed
     * before the cutoff; unresolved events are never removed.
     */
    public int deleteProcessedBefore(LocalDateTime cutoff) {
        return jdbcTemplate.update(
                "DELETE FROM stripe_event_entity "
                        + "WHERE status = 'PROCESSED' AND processed_at IS NOT NULL AND processed_at < ?",
                Timestamp.valueOf(cutoff));
    }
}
