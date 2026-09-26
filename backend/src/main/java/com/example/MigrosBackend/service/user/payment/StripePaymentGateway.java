package com.example.MigrosBackend.service.user.payment;

import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Refund;

import java.util.Optional;

public interface StripePaymentGateway {

    /**
     * Creates a charge with a mandatory server-derived idempotency key. A retry
     * of the same checkout must pass the same key so Stripe replays the original
     * result instead of creating a second economic charge.
     */
    Charge charge(String sourceToken, long amountMinor, String currency,
                  String idempotencyKey, String checkoutId) throws StripeException;

    /**
     * Reconciles a charge that may have been accepted by Stripe before the local
     * result was persisted, without any stored card token.
     */
    Optional<Charge> findChargeForCheckout(String checkoutId) throws StripeException;

    /**
     * Refunds a captured charge. The idempotency key makes repeated refunds of
     * the same attempt safe.
     */
    Refund refund(String chargeId, String idempotencyKey) throws StripeException;
}
