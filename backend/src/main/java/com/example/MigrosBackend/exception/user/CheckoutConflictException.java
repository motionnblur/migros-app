package com.example.MigrosBackend.exception.user;

import java.util.UUID;

/**
 * Typed checkout/payment state conflict that carries a stable machine-readable
 * code so clients never need to parse exception message text.
 */
public class CheckoutConflictException extends CheckoutStateException {

    public static final String RECONCILIATION_PENDING_CODE = "PAYMENT_RECONCILIATION_PENDING";
    public static final String NOT_CANCELLABLE_CODE = "CHECKOUT_NOT_CANCELLABLE";

    private final String code;
    private final boolean pending;
    private final UUID checkoutId;

    public CheckoutConflictException(String code, boolean pending, UUID checkoutId, String message) {
        super(message);
        this.code = code;
        this.pending = pending;
        this.checkoutId = checkoutId;
    }

    public String getCode() {
        return code;
    }

    public boolean isPending() {
        return pending;
    }

    public UUID getCheckoutId() {
        return checkoutId;
    }

    public static CheckoutConflictException reconciliationPending(UUID checkoutId, String message) {
        return new CheckoutConflictException(RECONCILIATION_PENDING_CODE, true, checkoutId, message);
    }

    public static CheckoutConflictException notCancellable(UUID checkoutId, String message) {
        return new CheckoutConflictException(NOT_CANCELLABLE_CODE, false, checkoutId, message);
    }
}
