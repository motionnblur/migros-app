package com.example.MigrosBackend.entity.payment;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Central definition of the durable payment-attempt state machine. Every
 * transition in the application goes through {@link #canTransitionTo} so that an
 * illegal or backward transition fails closed instead of silently corrupting
 * the record of whether money moved.
 */
public enum PaymentAttemptStatus {
    CREATED,
    PROCESSING,
    CHARGE_SUCCEEDED,
    ORDER_FINALIZED,
    FAILED_FINAL,
    REFUND_PENDING,
    REFUNDED,
    MANUAL_REVIEW;

    private static final Map<PaymentAttemptStatus, Set<PaymentAttemptStatus>> ALLOWED = Map.of(
            CREATED, EnumSet.of(PROCESSING, FAILED_FINAL, MANUAL_REVIEW),
            PROCESSING, EnumSet.of(CHARGE_SUCCEEDED, FAILED_FINAL, MANUAL_REVIEW),
            CHARGE_SUCCEEDED, EnumSet.of(ORDER_FINALIZED, REFUND_PENDING, MANUAL_REVIEW),
            ORDER_FINALIZED, EnumSet.of(REFUND_PENDING, REFUNDED, MANUAL_REVIEW),
            FAILED_FINAL, EnumSet.of(MANUAL_REVIEW),
            REFUND_PENDING, EnumSet.of(REFUNDED, MANUAL_REVIEW),
            REFUNDED, EnumSet.of(MANUAL_REVIEW),
            MANUAL_REVIEW, EnumSet.of(REFUND_PENDING, REFUNDED, FAILED_FINAL));

    public boolean canTransitionTo(PaymentAttemptStatus next) {
        if (next == null) {
            return false;
        }
        if (next == this) {
            return true;
        }
        return ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }

    public boolean hasDurableCharge() {
        return this == CHARGE_SUCCEEDED || this == ORDER_FINALIZED
                || this == REFUND_PENDING || this == REFUNDED;
    }

    /** A successful charge that must not be treated as an ordinary decline. */
    public boolean isRecoverable() {
        return this == PROCESSING || this == CHARGE_SUCCEEDED;
    }

    public boolean isTerminal() {
        return this == ORDER_FINALIZED || this == FAILED_FINAL
                || this == REFUNDED || this == MANUAL_REVIEW;
    }

    public boolean isFinalized() {
        return this == ORDER_FINALIZED;
    }
}
