package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.CheckoutStatusDto;
import com.example.MigrosBackend.dto.payment.PaymentClaim;
import com.example.MigrosBackend.dto.payment.PaymentClaimDecision;
import com.example.MigrosBackend.dto.payment.PaymentStatusDto;
import com.example.MigrosBackend.entity.checkout.CheckoutEntity;
import com.example.MigrosBackend.entity.checkout.CheckoutStatus;
import com.example.MigrosBackend.entity.payment.PaymentAttemptEntity;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;

import java.util.UUID;

/**
 * Pure payment-attempt DTO assembly, extracted verbatim from
 * {@link PaymentAttemptService}. No persistence, no provider, no state
 * transitions.
 */
final class PaymentAttemptMapper {

    private PaymentAttemptMapper() {
    }

    static PaymentClaim describe(PaymentAttemptEntity attempt,
                                 PaymentClaimDecision decision,
                                 String leaseOwner) {
        return new PaymentClaim(
                decision,
                attempt.getId(),
                attempt.getCheckoutId(),
                attempt.getIdempotencyKey(),
                attempt.getAmountMinor(),
                attempt.getCurrency(),
                leaseOwner,
                attempt.getStripeChargeId(),
                attempt.getStatus());
    }

    static CheckoutStatusDto toStatus(PaymentAttemptEntity attempt) {
        return new CheckoutStatusDto(
                attempt.getCheckoutId().toString(),
                attempt.getStatus().name(),
                null,
                attempt.getAmountMinor(),
                attempt.getCurrency(),
                null,
                null,
                null,
                attempt.getStripeChargeId());
    }

    static PaymentStatusDto statusWithoutAttempt(UUID checkoutId,
                                                 CheckoutEntity checkout,
                                                 CheckoutStatus checkoutState) {
        return new PaymentStatusDto(
                checkoutId.toString(), null, checkoutState.name(), null,
                checkout.getStripeChargeId(), checkout.getTotalAmount(), checkout.getAmountMinor(),
                checkout.getCurrency(), checkout.getOrderGroupEntityId(),
                checkoutState == CheckoutStatus.CONSUMED, false, false);
    }

    static PaymentStatusDto statusWithAttempt(UUID checkoutId,
                                              CheckoutStatusDto checkoutStatus,
                                              PaymentAttemptEntity attempt,
                                              CheckoutEntity checkout) {
        boolean finalized = attempt.getStatus().isFinalized();
        boolean pending = attempt.getStatus() == PaymentAttemptStatus.PROCESSING
                || attempt.getStatus() == PaymentAttemptStatus.CHARGE_SUCCEEDED;
        boolean refunded = attempt.getStatus() == PaymentAttemptStatus.REFUNDED;
        return new PaymentStatusDto(
                checkoutId.toString(),
                attempt.getId().toString(),
                checkoutStatus.status(),
                attempt.getStatus().name(),
                attempt.getStripeChargeId(),
                checkout.getTotalAmount(),
                attempt.getAmountMinor(),
                attempt.getCurrency(),
                checkout.getOrderGroupEntityId(),
                finalized,
                pending,
                refunded);
    }
}
