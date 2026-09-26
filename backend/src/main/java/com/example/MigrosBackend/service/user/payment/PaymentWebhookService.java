package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.PaymentClaim;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;
import com.example.MigrosBackend.exception.user.PaymentStateException;
import com.example.MigrosBackend.service.user.payment.StripeEventStore.ReceiveOutcome;
import com.example.MigrosBackend.service.user.payment.StripeEventStore.StoredEvent;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.model.Charge;
import com.stripe.model.Dispute;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.net.ApiResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies signature-verified Stripe webhook events to the durable payment
 * attempt through a crash-safe inbox.
 *
 * <p>At-least-once processing with exactly-once effects:
 *
 * <ul>
 *   <li>Receipt ({@code RECEIVED}) and processing are separate commits. A
 *   crash after receipt but before processing leaves the event reclaimable;
 *   row existence alone never counts as completion, only {@code PROCESSED}
 *   does.</li>
 *   <li>Claiming uses an atomic conditional update with a bounded lease, so
 *   concurrent duplicate deliveries converge on a single worker and an
 *   expired lease can be reclaimed.</li>
 *   <li>All effects (charge transitions, order finalization, refunds) are
 *   idempotent, so a crash after effects commit but before {@code PROCESSED}
 *   is harmless on retry.</li>
 *   <li>A redelivery whose type or payload hash differs from the stored row
 *   fails closed into manual review without applying effects.</li>
 * </ul>
 *
 * <p>Events are mapped to stored attempts by provider charge id first, then by
 * the checkout id embedded in the signed charge metadata. Out-of-order or
 * duplicate events can never regress a final state because all transitions go
 * through the central state machine.
 *
 * <p>Only event ids, types, and sanitized error codes are logged. Verified
 * payloads, signatures, and secrets never enter logs.
 */
@Service
public class PaymentWebhookService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentWebhookService.class);

    private final StripeEventStore stripeEventStore;
    private final PaymentAttemptService paymentAttemptService;
    private final PaymentFinalizationService paymentFinalizationService;
    private final Clock clock;
    private final long leaseSeconds;
    private final int maxAttempts;
    private final long backoffBaseSeconds;
    private final long backoffMaxSeconds;

    public PaymentWebhookService(StripeEventStore stripeEventStore,
                                 PaymentAttemptService paymentAttemptService,
                                 PaymentFinalizationService paymentFinalizationService,
                                 Clock clock,
                                 @Value("${payment.webhook.inbox-lease-seconds:120}") long leaseSeconds,
                                 @Value("${payment.webhook.inbox-max-attempts:8}") int maxAttempts,
                                 @Value("${payment.webhook.inbox-backoff-base-seconds:60}") long backoffBaseSeconds,
                                 @Value("${payment.webhook.inbox-backoff-max-seconds:3600}") long backoffMaxSeconds) {
        this.stripeEventStore = stripeEventStore;
        this.paymentAttemptService = paymentAttemptService;
        this.paymentFinalizationService = paymentFinalizationService;
        this.clock = clock;
        this.leaseSeconds = leaseSeconds;
        this.maxAttempts = maxAttempts;
        this.backoffBaseSeconds = backoffBaseSeconds;
        this.backoffMaxSeconds = backoffMaxSeconds;
    }

    /**
     * Handles one verified delivery. The event must already have passed
     * Stripe signature verification over {@code rawPayload}; this method
     * inserts it into the inbox and processes it when it wins the claim.
     * A processing failure parks the event for a bounded retry and rethrows
     * so Stripe redelivers; redelivery is always safe.
     */
    public void handle(Event event, String rawPayload) {
        if (event == null || event.getId() == null || event.getType() == null) {
            LOG.warn("Ignoring Stripe webhook without an id or type");
            return;
        }
        String eventId = event.getId();
        String eventType = event.getType();
        LocalDateTime now = LocalDateTime.now(clock);
        String payload = rawPayload == null ? "" : rawPayload;

        ReceiveOutcome outcome = stripeEventStore.receive(
                eventId, eventType, rawPayload, sha256Hex(payload), now);
        switch (outcome) {
            case CONFLICT -> {
                stripeEventStore.markManualReview(eventId, "event_id_collision");
                LOG.error("Stripe event id collision for {}; held for manual review", eventId);
                return;
            }
            case ALREADY_PROCESSED -> {
                LOG.info("Ignoring already-processed Stripe event {} of type {}", eventId, eventType);
                return;
            }
            case RECEIVED_NEW, NEEDS_PROCESSING -> {
                // Fall through to the atomic claim below.
            }
        }

        Optional<StoredEvent> claim =
                stripeEventStore.tryClaim(eventId, UUID.randomUUID().toString(), now, leaseSeconds);
        if (claim.isEmpty()) {
            LOG.info("Stripe event {} is already claimed by another worker", eventId);
            return;
        }
        StoredEvent claimed = claim.get();
        afterClaim(eventId);

        Event effective = event;
        if (claimed.payload() != null) {
            try {
                effective = parseStoredPayload(eventId, claimed.payload());
            } catch (RuntimeException ex) {
                recordFailure(eventId, claimed.attemptCount(), ex);
                throw ex;
            }
        }

        try {
            process(effective, effective.getType() != null ? effective.getType() : eventType);
            afterEffects(eventId);
            if (!stripeEventStore.markProcessed(eventId, LocalDateTime.now(clock))) {
                LOG.warn("Stripe event {} could not be marked processed; "
                        + "it was reclaimed or held for review", eventId);
            }
        } catch (RuntimeException ex) {
            recordFailure(eventId, claimed.attemptCount(), ex);
            throw ex;
        }
    }

    /**
     * Replays one stored event for the scheduled recovery job. Returns true
     * when this worker completed the event; false when another worker holds
     * the lease or the attempt was parked for retry/review. Never throws for
     * event-processing failures; they are recorded in the inbox row.
     */
    public boolean processStoredEvent(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Optional<StoredEvent> claim =
                stripeEventStore.tryClaim(eventId, UUID.randomUUID().toString(), now, leaseSeconds);
        if (claim.isEmpty()) {
            return false;
        }
        StoredEvent claimed = claim.get();
        if (claimed.payload() == null || claimed.payload().isBlank()) {
            stripeEventStore.markManualReview(eventId, "missing_payload");
            LOG.error("Stripe event {} has no stored payload; held for manual review", eventId);
            return false;
        }
        Event event;
        try {
            event = parseStoredPayload(eventId, claimed.payload());
        } catch (RuntimeException ex) {
            recordFailure(eventId, claimed.attemptCount(), ex);
            return false;
        }
        try {
            process(event, event.getType() != null ? event.getType() : claimed.eventType());
            afterEffects(eventId);
            return stripeEventStore.markProcessed(eventId, LocalDateTime.now(clock));
        } catch (RuntimeException ex) {
            recordFailure(eventId, claimed.attemptCount(), ex);
            return false;
        }
    }

    /**
     * Fault-injection hook called after a claim is won and before effects run.
     * No-op in production; tests stub it to simulate a crash after receipt.
     */
    void afterClaim(String eventId) {
    }

    /**
     * Fault-injection hook called after effects commit and before the inbox
     * row is marked processed. No-op in production; tests stub it to simulate
     * a crash between effects and completion.
     */
    void afterEffects(String eventId) {
    }

    /**
     * Parses only the payload already verified and stored for the event, so a
     * worker can never act on content that bypassed signature verification.
     */
    private Event parseStoredPayload(String eventId, String payload) {
        try {
            Event event = StripeObject.deserializeStripeObject(
                    payload, Event.class, ApiResource.getGlobalResponseGetter());
            if (event == null || event.getId() == null || event.getType() == null) {
                throw new IllegalStateException("stored payload is missing its id or type");
            }
            return event;
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "Stored webhook payload for event " + eventId + " cannot be replayed", ex);
        }
    }

    private void recordFailure(String eventId, int attemptCount, RuntimeException ex) {
        String failureClass = ex.getClass().getSimpleName();
        if (attemptCount >= maxAttempts) {
            stripeEventStore.markManualReview(eventId, sanitizeCode("exhausted:" + failureClass));
            LOG.error("Stripe event {} exhausted {} attempts ({}); held for manual review",
                    eventId, attemptCount, failureClass);
            return;
        }
        long shift = Math.min(Math.max(attemptCount - 1, 0), 20);
        long backoff = Math.min(backoffBaseSeconds * (1L << shift), backoffMaxSeconds);
        LocalDateTime nextAttempt = LocalDateTime.now(clock).plusSeconds(backoff);
        stripeEventStore.markFailed(eventId, sanitizeCode("effect_failed:" + failureClass), nextAttempt);
        LOG.warn("Stripe event {} processing failed ({}); retry {}/{} scheduled",
                eventId, failureClass, attemptCount, maxAttempts);
    }

    static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required for webhook payload hashing", ex);
        }
    }

    private String sanitizeCode(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= 64 ? trimmed : trimmed.substring(0, 64);
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
