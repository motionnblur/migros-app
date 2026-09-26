package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.PaymentClaim;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;
import com.example.MigrosBackend.exception.user.PaymentStateException;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.model.Charge;
import com.stripe.model.Dispute;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Applies signature-verified Stripe webhook events to the durable payment
 * attempt. Events are deduplicated by event id and mapped to stored attempts by
 * provider charge id first, then by the checkout id embedded in the signed
 * charge metadata. Out-of-order or duplicate events can never regress a final
 * state because all transitions go through the central state machine.
 */
@Service
public class PaymentWebhookService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentWebhookService.class);

    private final StripeEventStore stripeEventStore;
    private final PaymentAttemptService paymentAttemptService;
    private final PaymentFinalizationService paymentFinalizationService;

    public PaymentWebhookService(StripeEventStore stripeEventStore,
                                 PaymentAttemptService paymentAttemptService,
                                 PaymentFinalizationService paymentFinalizationService) {
        this.stripeEventStore = stripeEventStore;
        this.paymentAttemptService = paymentAttemptService;
        this.paymentFinalizationService = paymentFinalizationService;
    }

    public void handle(Event event) {
        if (event == null || event.getId() == null || event.getType() == null) {
            LOG.warn("Ignoring Stripe webhook without an id or type");
            return;
        }
        String eventId = event.getId();
        String eventType = event.getType();

        if (!stripeEventStore.markIfNew(eventId, eventType, LocalDateTime.now())) {
            LOG.info("Ignoring duplicate Stripe event {} of type {}", eventId, eventType);
            return;
        }

        try {
            process(event, eventType);
            stripeEventStore.markProcessed(eventId);
        } catch (RuntimeException ex) {
            stripeEventStore.remove(eventId);
            throw ex;
        }
    }

    private void process(Event event, String eventType) {
        switch (eventType) {
            case "charge.succeeded" -> handleChargeSucceeded(chargeOf(event));
            case "charge.failed" -> handleChargeFailed(chargeOf(event));
            case "charge.refunded" -> handleChargeRefunded(chargeOf(event));
            case "charge.dispute.created", "charge.dispute.funds_withdrawn", "charge.dispute.closed" ->
                    handleDispute(event);
            default -> LOG.debug("Ignoring unhandled Stripe event type {}", eventType);
        }
    }

    private void handleChargeSucceeded(Charge charge) {
        if (charge == null) {
            return;
        }
        PaymentClaim claim = resolveAttempt(charge);
        if (claim == null) {
            return;
        }
        PaymentAttemptStatus state = claim.state();
        try {
            if (state == PaymentAttemptStatus.PROCESSING || state == PaymentAttemptStatus.CREATED) {
                paymentAttemptService.recordChargeSuccess(claim.attemptId(), null, charge.getId());
            } else if (state != PaymentAttemptStatus.CHARGE_SUCCEEDED
                    && state != PaymentAttemptStatus.ORDER_FINALIZED) {
                return;
            }
            paymentFinalizationService.finalizeOrder(claim.attemptId(), claim.checkoutId(), charge.getId());
        } catch (PaymentStateException ex) {
            LOG.warn("Webhook charge.succeeded could not be applied to attempt {}: {}",
                    claim.attemptId(), ex.getMessage());
        }
    }

    private void handleChargeFailed(Charge charge) {
        if (charge == null) {
            return;
        }
        PaymentClaim claim = resolveAttempt(charge);
        if (claim == null) {
            return;
        }
        PaymentAttemptStatus state = claim.state();
        if (state != PaymentAttemptStatus.PROCESSING && state != PaymentAttemptStatus.CREATED) {
            return;
        }
        try {
            paymentAttemptService.recordDecline(claim.attemptId(), charge.getFailureCode());
        } catch (PaymentStateException ex) {
            LOG.warn("Webhook charge.failed could not be applied to attempt {}: {}",
                    claim.attemptId(), ex.getMessage());
        }
    }

    private void handleChargeRefunded(Charge charge) {
        if (charge == null) {
            return;
        }
        PaymentClaim claim = resolveAttempt(charge);
        if (claim == null || claim.state() == PaymentAttemptStatus.REFUNDED) {
            return;
        }
        try {
            if (claim.state() == PaymentAttemptStatus.PROCESSING
                    || claim.state() == PaymentAttemptStatus.CREATED) {
                // A refund cannot arrive before the charge is recorded; keep the
                // state forward-only and let an operator reconcile.
                paymentAttemptService.markManualReview(claim.attemptId(), "refund_before_charge");
                return;
            }
            paymentAttemptService.recordRefunded(claim.attemptId(), charge.getId());
        } catch (PaymentStateException ex) {
            LOG.warn("Webhook charge.refunded could not be applied to attempt {}: {}",
                    claim.attemptId(), ex.getMessage());
        }
    }

    private void handleDispute(Event event) {
        StripeObject object = deserialize(event);
        if (!(object instanceof Dispute dispute) || dispute.getCharge() == null) {
            return;
        }
        PaymentClaim claim = paymentAttemptService.findByProviderCharge(dispute.getCharge());
        if (claim == null) {
            LOG.warn("Dispute for unknown charge {} could not be mapped to an attempt",
                    dispute.getCharge());
            return;
        }
        try {
            paymentAttemptService.markManualReview(claim.attemptId(), "stripe_dispute");
        } catch (PaymentStateException ex) {
            LOG.warn("Webhook dispute could not be applied to attempt {}: {}",
                    claim.attemptId(), ex.getMessage());
        }
    }

    private PaymentClaim resolveAttempt(Charge charge) {
        if (charge.getId() != null) {
            PaymentClaim byCharge = paymentAttemptService.findByProviderCharge(charge.getId());
            if (byCharge != null) {
                return verifyEconomics(byCharge, charge);
            }
        }
        String metadataCheckoutId = charge.getMetadata() == null
                ? null
                : charge.getMetadata().get(StripePaymentGatewayImpl.CHECKOUT_ID_METADATA_KEY);
        if (metadataCheckoutId == null) {
            LOG.warn("Stripe charge {} is not linked to a known attempt or checkout", charge.getId());
            return null;
        }
        UUID checkoutId;
        try {
            checkoutId = UUID.fromString(metadataCheckoutId);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        PaymentClaim claim = paymentAttemptService.findByCheckoutId(checkoutId);
        if (claim == null) {
            return null;
        }
        return verifyEconomics(claim, charge);
    }

    private PaymentClaim verifyEconomics(PaymentClaim claim, Charge charge) {
        boolean amountMatches = Objects.equals(claim.amountMinor(), charge.getAmount());
        boolean currencyMatches = claim.currency() == null || charge.getCurrency() == null
                || claim.currency().equalsIgnoreCase(charge.getCurrency());
        if (!amountMatches || !currencyMatches) {
            LOG.error("Stripe event economics do not match attempt {}; moving to manual review",
                    claim.attemptId());
            paymentAttemptService.markManualReview(claim.attemptId(), "provider_amount_mismatch");
            return null;
        }
        return claim;
    }

    private Charge chargeOf(Event event) {
        StripeObject object = deserialize(event);
        return object instanceof Charge charge ? charge : null;
    }

    private StripeObject deserialize(Event event) {
        StripeObject object = event.getDataObjectDeserializer().getObject().orElse(null);
        if (object != null) {
            return object;
        }
        try {
            return event.getDataObjectDeserializer().deserializeUnsafe();
        } catch (EventDataObjectDeserializationException ex) {
            LOG.warn("Could not deserialize Stripe event {}", event.getId());
            return null;
        }
    }
}
