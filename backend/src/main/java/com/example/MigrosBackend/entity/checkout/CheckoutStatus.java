package com.example.MigrosBackend.entity.checkout;

import java.util.List;

public enum CheckoutStatus {
    PREPARED,
    PAYMENT_PROCESSING,
    PAID,
    CONSUMED,
    CANCELLED,
    EXPIRED;

    public static List<CheckoutStatus> liveStatuses() {
        return List.of(PREPARED, PAYMENT_PROCESSING);
    }

    /**
     * A checkout that is safe to expire and release: a processing checkout may
     * have a charge in flight and must not have its reservation released.
     */
    public static List<CheckoutStatus> expirableStatuses() {
        return List.of(PREPARED);
    }

    public boolean isLive() {
        return this == PREPARED || this == PAYMENT_PROCESSING;
    }

    public boolean isTerminal() {
        return this == CONSUMED || this == CANCELLED || this == EXPIRED;
    }
}
