package com.example.MigrosBackend.service.user.payment;

import java.util.UUID;

/**
 * Server-derived, stable idempotency keys. The client never supplies these; a
 * retry of the same checkout/attempt always derives the same key.
 */
public final class ChargeIdempotencyKeys {

    private static final String CHARGE_VERSION = "charge-v1";
    private static final String REFUND_VERSION = "refund-v1";

    private ChargeIdempotencyKeys() {
    }

    public static String forCheckout(UUID checkoutId) {
        if (checkoutId == null) {
            throw new IllegalArgumentException("checkoutId is required");
        }
        return "checkout:" + checkoutId + ":" + CHARGE_VERSION;
    }

    public static String refundForAttempt(UUID attemptId) {
        if (attemptId == null) {
            throw new IllegalArgumentException("attemptId is required");
        }
        return "attempt:" + attemptId + ":" + REFUND_VERSION;
    }
}
