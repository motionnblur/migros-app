package com.example.MigrosBackend.entity.support;

/**
 * Lifecycle of one durable support-service outbox record.
 *
 * <p>Only {@link #DELIVERED} means the support service has the event. Every
 * other state is work that is still owed and is reclaimable: a worker that dies
 * mid-delivery leaves {@link #PROCESSING} with an expired lease, and a failed
 * attempt leaves {@link #PENDING} with a future {@code next_attempt_at}.
 * {@link #FAILED} is the terminal state for an event that exhausted its
 * attempts and needs operator attention.
 */
public enum SupportOutboxStatus {
    PENDING,
    PROCESSING,
    DELIVERED,
    FAILED
}
