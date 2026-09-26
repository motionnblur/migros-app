package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.PaymentClaim;
import com.example.MigrosBackend.dto.payment.PaymentResponseDto;
import com.example.MigrosBackend.dto.payment.PaymentStatusDto;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.exception.user.PaymentStateException;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Orchestrates the charge workflow: a short transactional claim, then the
 * Stripe network call outside any database transaction, then durable persistence
 * of the provider result before local order finalization.
 */
@Service
public class UserPaymentService {

    private static final Logger LOG = LoggerFactory.getLogger(UserPaymentService.class);

    private final StripePaymentGateway stripePaymentGateway;
    private final PaymentAttemptService paymentAttemptService;
    private final PaymentFinalizationService paymentFinalizationService;

    public UserPaymentService(StripePaymentGateway stripePaymentGateway,
                              PaymentAttemptService paymentAttemptService,
                              PaymentFinalizationService paymentFinalizationService) {
        this.stripePaymentGateway = stripePaymentGateway;
        this.paymentAttemptService = paymentAttemptService;
        this.paymentFinalizationService = paymentFinalizationService;
    }

    public PaymentResponseDto processCharge(UUID checkoutId, String paymentToken, String userToken) {
        if (paymentToken == null || paymentToken.isBlank()) {
            throw new GeneralException("Payment token is required");
        }

        String idempotencyKey = ChargeIdempotencyKeys.forCheckout(checkoutId);
        PaymentClaim claim = paymentAttemptService.claim(userToken, checkoutId, idempotencyKey);

        return switch (claim.decision()) {
            case FINALIZED -> finalizedResponse(claim, userToken, checkoutId);
            case TERMINAL -> terminalResponse(claim, userToken, checkoutId);
            case PENDING -> pendingResponse(claim, userToken, checkoutId,
                    "Payment is already being processed");
            case FINALIZE -> finalizeExistingCharge(claim, userToken, checkoutId, claim.chargeId());
            case PROCEED -> chargeWithProvider(claim, paymentToken, userToken, checkoutId);
        };
    }

    /**
     * Owner-scoped status lookup used for polling and for recovering ambiguous
     * network outcomes. If the charge is durably recorded but the order has not
     * been finalized, finalization is retried here.
     */
    public PaymentStatusDto getPaymentStatus(String userToken, UUID checkoutId) {
        PaymentStatusDto status = paymentAttemptService.getStatus(userToken, checkoutId);
        if (PaymentAttemptStatus.CHARGE_SUCCEEDED.name().equals(status.state())) {
            retryFinalization(userToken, checkoutId, status);
            status = paymentAttemptService.getStatus(userToken, checkoutId);
        }
        return status;
    }

    private PaymentResponseDto chargeWithProvider(PaymentClaim claim,
                                                  String paymentToken,
                                                  String userToken,
                                                  UUID checkoutId) {
        Charge charge;
        try {
            charge = stripePaymentGateway.charge(
                    paymentToken,
                    claim.amountMinor(),
                    claim.currency(),
                    claim.idempotencyKey(),
                    checkoutId.toString());
        } catch (CardException | InvalidRequestException declined) {
            // The provider explicitly rejected the charge: no money moved, so the
            // reservation may be released and this attempt is terminally failed.
            paymentAttemptService.recordDecline(claim.attemptId(), safeErrorCode(declined));
            return terminalResponse(claim, userToken, checkoutId);
        } catch (StripeException ambiguous) {
            // Connection/API errors are ambiguous: the charge may or may not have
            // been recorded by Stripe. Keep the attempt processing and let the
            // status endpoint, webhook, or reconciliation job resolve it.
            LOG.warn("Ambiguous Stripe outcome for attempt {} checkout {}: {}",
                    claim.attemptId(), checkoutId, ambiguous.getClass().getSimpleName());
            PaymentStatusDto status = paymentAttemptService.getStatus(userToken, checkoutId);
            return new PaymentResponseDto(
                    false, true, checkoutId.toString(), attemptId(claim), status.checkoutStatus(),
                    status.state(), status.chargeId(), status.totalAmount(), status.amountMinor(),
                    status.currency(), "Payment status is uncertain and will be reconciled");
        }

        if (charge == null || charge.getId() == null) {
            throw new PaymentStateException("Stripe returned no charge identifier");
        }

        // Persist the provider result before touching the order so a crash after
        // this point can never re-charge.
        paymentAttemptService.recordChargeSuccess(claim.attemptId(), claim.leaseOwner(), charge.getId());
        return finalizeExistingCharge(claim, userToken, checkoutId, charge.getId());
    }

    private PaymentResponseDto finalizeExistingCharge(PaymentClaim claim,
                                                      String userToken,
                                                      UUID checkoutId,
                                                      String chargeId) {
        boolean finalized = paymentFinalizationService.finalizeOrder(
                claim.attemptId(), checkoutId, chargeId);
        if (finalized) {
            return finalizedResponse(claim, userToken, checkoutId);
        }
        // Stripe already captured the money. Never report this as an ordinary
        // decline; the attempt stays recoverable and finalization retries.
        PaymentStatusDto status = paymentAttemptService.getStatus(userToken, checkoutId);
        return new PaymentResponseDto(
                true, true, checkoutId.toString(), attemptId(claim), status.checkoutStatus(),
                status.state() == null ? PaymentAttemptStatus.CHARGE_SUCCEEDED.name() : status.state(),
                chargeId, status.totalAmount(), status.amountMinor(), status.currency(),
                "Payment succeeded and is being finalized");
    }

    private void retryFinalization(String userToken, UUID checkoutId, PaymentStatusDto status) {
        if (status.attemptId() == null || status.chargeId() == null) {
            return;
        }
        UUID attemptId = UUID.fromString(status.attemptId());
        paymentFinalizationService.finalizeOrder(attemptId, checkoutId, status.chargeId());
    }

    private PaymentResponseDto finalizedResponse(PaymentClaim claim, String userToken, UUID checkoutId) {
        PaymentStatusDto status = paymentAttemptService.getStatus(userToken, checkoutId);
        return new PaymentResponseDto(
                true, false, checkoutId.toString(), attemptId(claim), status.checkoutStatus(),
                status.state(), status.chargeId(), status.totalAmount(), status.amountMinor(),
                status.currency(), null);
    }

    private PaymentResponseDto pendingResponse(PaymentClaim claim, String userToken, UUID checkoutId,
                                               String message) {
        PaymentStatusDto status = paymentAttemptService.getStatus(userToken, checkoutId);
        return new PaymentResponseDto(
                false, true, checkoutId.toString(), attemptId(claim), status.checkoutStatus(),
                status.state(), status.chargeId(), status.totalAmount(), status.amountMinor(),
                status.currency(), message);
    }

    private PaymentResponseDto terminalResponse(PaymentClaim claim, String userToken, UUID checkoutId) {
        PaymentStatusDto status = paymentAttemptService.getStatus(userToken, checkoutId);
        String message = switch (claim.state()) {
            case FAILED_FINAL -> "Payment was declined by the provider";
            case REFUND_PENDING, REFUNDED -> "Payment was refunded";
            case MANUAL_REVIEW -> "Payment requires manual review";
            default -> "Payment cannot be completed for this checkout";
        };
        return new PaymentResponseDto(
                false, false, checkoutId.toString(), attemptId(claim), status.checkoutStatus(),
                status.state(), status.chargeId(), status.totalAmount(), status.amountMinor(),
                status.currency(), message);
    }

    private String attemptId(PaymentClaim claim) {
        return claim.attemptId() == null ? null : claim.attemptId().toString();
    }

    private String safeErrorCode(StripeException exception) {
        if (exception instanceof CardException card && card.getCode() != null) {
            return card.getCode();
        }
        return "provider_rejected";
    }
}
