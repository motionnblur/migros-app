package com.example.MigrosBackend.entity.support;

/**
 * Lifecycle of one durable support-service outbox record.
 *
 * <p>Only {@link #DELIVERED} means the support service has the event. Every
 * other state is work that is still owed and is reclaimable: a worker that dies
 * mid-delivery leaves {@link #PROCESSING} with an expired lease, and a failed
 * attempt leaves {@link #PENDING} with a future {@code next_attempt_at}.
 *
 * <p>There is deliberately no "given up" state. A support event is owed for as
 * long as the receiver has not acknowledged it, and parking it terminally would
 * both lose the event and unblock the customer's queue behind it, because
 * per-customer ordering only holds back later events while an earlier one is
 * still {@code PENDING} or {@code PROCESSING}. Delivery therefore retries
 * forever, with a logging threshold for operator attention rather than a budget.
 */
public enum SupportOutboxStatus {
    PENDING,
    PROCESSING,
    DELIVERED
}
