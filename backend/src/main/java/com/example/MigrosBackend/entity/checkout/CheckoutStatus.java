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

    /**
     * Only a PREPARED checkout may be cancelled by the user. A
     * PAYMENT_PROCESSING checkout may have money in flight and must keep its
     * reservation until the provider outcome is durably resolved.
     */
    public boolean isUserCancellable() {
        return this == PREPARED;
    }

    public boolean isTerminal() {
        return this == CONSUMED || this == CANCELLED || this == EXPIRED;
    }
}
