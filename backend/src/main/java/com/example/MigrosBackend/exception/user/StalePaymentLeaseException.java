package com.example.MigrosBackend.exception.user;

/**
 * Typed stale-claim outcome for lease-fenced payment transitions. Thrown when
 * a request worker presents a lease token that is {@code null}, blank, or no
 * longer equal to the currently stored lease owner — i.e. another worker has
 * reclaimed the attempt and the caller must not alter payment, checkout,
 * order, or stock state.
 *
 * <p>Extends {@link PaymentStateException} so existing conflict handling (HTTP
 * 409 via {@code GlobalExceptionHandler}, webhook/recovery fail-closed paths)
 * keeps working, while allowing callers to distinguish a fenced-out worker
 * from other illegal payment transitions.
 */
public class StalePaymentLeaseException extends PaymentStateException {
    public StalePaymentLeaseException(String message) {
        super(message);
    }
}
