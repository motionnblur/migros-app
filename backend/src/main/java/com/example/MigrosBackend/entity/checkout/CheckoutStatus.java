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

    public boolean isLive() {
        return this == PREPARED || this == PAYMENT_PROCESSING;
    }

    public boolean isTerminal() {
        return this == CONSUMED || this == CANCELLED || this == EXPIRED;
    }
}
