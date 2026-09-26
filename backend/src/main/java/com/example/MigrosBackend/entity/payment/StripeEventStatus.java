package com.example.MigrosBackend.entity.payment;

/**
 * Lifecycle of a durable Stripe webhook inbox row. An event is complete only
 * when {@code PROCESSED}; every other non-terminal state is reclaimable work.
 */
public enum StripeEventStatus {
    /** Verified and stored, never yet claimed by a worker. */
    RECEIVED,
    /** Claimed by exactly one worker holding a bounded lease. */
    PROCESSING,
    /** Effects applied and durably recorded. Terminal. */
    PROCESSED,
    /** Processing failed; retryable once {@code next_attempt_at} passes. */
    FAILED,
    /** Exhausted retries or conflicting content; needs an operator. Terminal. */
    MANUAL_REVIEW;

    public boolean isComplete() {
        return this == PROCESSED;
    }

    public boolean isTerminal() {
        return this == PROCESSED || this == MANUAL_REVIEW;
    }
}
