package com.example.MigrosBackend.service.user.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Durable Stripe webhook event deduplication. The insert uses a single atomic
 * {@code ON CONFLICT DO NOTHING} so concurrent duplicate deliveries can never
 * both be treated as new.
 */
@Component
public class StripeEventStore {

    private final JdbcTemplate jdbcTemplate;

    public StripeEventStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean markIfNew(String eventId, String eventType, LocalDateTime receivedAt) {
        int rows = jdbcTemplate.update(
                "INSERT INTO stripe_event_entity (event_id, event_type, received_at) "
                        + "VALUES (?, ?, ?) ON CONFLICT (event_id) DO NOTHING",
                eventId, eventType, Timestamp.valueOf(receivedAt));
        return rows == 1;
    }

    public void markProcessed(String eventId) {
        jdbcTemplate.update(
                "UPDATE stripe_event_entity SET processed_at = ? WHERE event_id = ?",
                Timestamp.valueOf(LocalDateTime.now()), eventId);
    }

    public void remove(String eventId) {
        jdbcTemplate.update("DELETE FROM stripe_event_entity WHERE event_id = ?", eventId);
    }
}
