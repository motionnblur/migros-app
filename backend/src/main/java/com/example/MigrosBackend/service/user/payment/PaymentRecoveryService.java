package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.PaymentClaim;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;
import com.example.MigrosBackend.exception.user.PaymentStateException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Refund;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded recovery for attempts that are stuck. The provider call always
 * happens outside a database transaction and every state change is idempotent,
 * so recovery cannot create a second economic charge or a second refund.
 */
@Service
public class PaymentRecoveryService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentRecoveryService.class);

    private final PaymentAttemptService paymentAttemptService;
    private final PaymentFinalizationService paymentFinalizationService;
    private final StripePaymentGateway stripePaymentGateway;

    public PaymentRecoveryService(PaymentAttemptService paymentAttemptService,
                                  PaymentFinalizationService paymentFinalizationService,
                                  StripePaymentGateway stripePaymentGateway) {
        this.paymentAttemptService = paymentAttemptService;
        this.paymentFinalizationService = paymentFinalizationService;
        this.stripePaymentGateway = stripePaymentGateway;
    }

    public void recover(UUID attemptId) {
        PaymentClaim claim = paymentAttemptService.describeForRecovery(attemptId);
        if (claim == null) {
            return;
        }
        switch (claim.state()) {
            case PROCESSING -> reconcileProcessing(claim);
            case CHARGE_SUCCEEDED -> paymentFinalizationService.finalizeProviderOrder(
                    claim.attemptId(), claim.checkoutId(), claim.chargeId());
            case REFUND_PENDING -> refund(attemptId, claim.chargeId(), "recovery");
            default -> LOG.debug("No recovery needed for attempt {} in state {}",
                    attemptId, claim.state());
        }
    }

    /**
     * Idempotent refund. The refund idempotency key is derived from the attempt
     * id, so a repeated call or crash cannot issue a second refund.
     */
    public boolean refund(UUID attemptId, String chargeId, String reason) {
        if (chargeId == null || chargeId.isBlank()) {
            paymentAttemptService.markManualReview(attemptId, "refund_without_charge");
            return false;
        }
        if (!paymentAttemptService.markRefundPending(attemptId, reason)) {
            return true;
        }
        try {
            Refund refund = stripePaymentGateway.refund(
                    chargeId, ChargeIdempotencyKeys.refundForAttempt(attemptId));
            paymentAttemptService.recordRefunded(attemptId, refund == null ? null : refund.getId());
            LOG.info("Refund {} recorded for attempt {}", refund == null ? null : refund.getId(), attemptId);
            return true;
        } catch (StripeException ex) {
            LOG.error("Refund failed for attempt {} charge {}: {}",
                    attemptId, chargeId, ex.getClass().getSimpleName());
            return false;
        } catch (PaymentStateException ex) {
            LOG.warn("Refund state rejected for attempt {}: {}", attemptId, ex.getMessage());
            return false;
        }
    }

    private void reconcileProcessing(PaymentClaim claim) {
        try {
            Optional<Charge> providerCharge =
                    stripePaymentGateway.findChargeForCheckout(claim.checkoutId().toString());
            if (providerCharge.isEmpty()) {
                LOG.error("Aged unresolved payment attempt {} for checkout {} has no provider charge yet",
                        claim.attemptId(), claim.checkoutId());
                return;
            }
            Charge charge = providerCharge.get();
            // Trusted provider-event path: the gateway lookup above is the
            // authority, but the attempt transition still re-verifies checkout
            // linkage plus exact amount and currency and stays forward-only,
            // so recovery can never overwrite newer durable state.
            if (!Objects.equals(claim.amountMinor(), charge.getAmount())
                    || charge.getCurrency() == null
                    || !charge.getCurrency().equalsIgnoreCase(claim.currency())) {
                LOG.error("Provider charge {} economics do not match attempt {}; manual review required",
                        charge.getId(), claim.attemptId());
                paymentAttemptService.markManualReview(claim.attemptId(), "provider_amount_mismatch");
                return;
            }
            if (!Boolean.TRUE.equals(charge.getPaid())) {
                LOG.warn("Provider charge {} for attempt {} is not paid; leaving recoverable",
                        charge.getId(), claim.attemptId());
                return;
            }
            paymentAttemptService.recordProviderChargeSuccess(
                    claim.attemptId(), charge.getId(), charge.getAmount(),
                    charge.getCurrency(), claim.checkoutId());
            paymentFinalizationService.finalizeProviderOrder(
                    claim.attemptId(), claim.checkoutId(), charge.getId());
        } catch (StripeException ex) {
            LOG.warn("Provider lookup failed for attempt {}: {}",
                    claim.attemptId(), ex.getClass().getSimpleName());
        } catch (PaymentStateException ex) {
            LOG.warn("Recovery transition rejected for attempt {}: {}",
                    claim.attemptId(), ex.getMessage());
        }
    }
}
