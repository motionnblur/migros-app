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
                stripeEventStore.markProviderCollisionReview(eventId, "event_id_collision");
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

        String claimToken = UUID.randomUUID().toString();
        Optional<StoredEvent> claim =
                stripeEventStore.tryClaim(eventId, claimToken, now, leaseSeconds);
        if (claim.isEmpty()) {
            LOG.info("Stripe event {} is already claimed by another worker", eventId);
            return;
        }
        StoredEvent claimed = claim.get();
        String leaseOwner = claimed.leaseOwner() != null ? claimed.leaseOwner() : claimToken;
        afterClaim(eventId);

        Event effective = event;
        if (claimed.payload() != null) {
            try {
                effective = parseStoredPayload(eventId, claimed.payload());
            } catch (RuntimeException ex) {
                if (recordFailure(eventId, leaseOwner, claimed.attemptCount(), ex)
                        == StripeEventStore.InboxTransition.STALE_CLAIM) {
                    LOG.warn("Stale Stripe worker stepping aside for event {}", eventId);
                    return;
                }
                throw ex;
            }
        }

        try {
            process(effective, effective.getType() != null ? effective.getType() : eventType);
            afterEffects(eventId);
            if (stripeEventStore.markProcessed(eventId, leaseOwner, LocalDateTime.now(clock))
                    == StripeEventStore.InboxTransition.STALE_CLAIM) {
                LOG.warn("Stale Stripe worker stepping aside for event {}; "
                        + "it was reclaimed or held for review", eventId);
            }
        } catch (RuntimeException ex) {
            if (recordFailure(eventId, leaseOwner, claimed.attemptCount(), ex)
                    == StripeEventStore.InboxTransition.STALE_CLAIM) {
                LOG.warn("Stale Stripe worker stepping aside for event {}", eventId);
                return;
            }
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
        String claimToken = UUID.randomUUID().toString();
        Optional<StoredEvent> claim =
                stripeEventStore.tryClaim(eventId, claimToken, now, leaseSeconds);
        if (claim.isEmpty()) {
            return false;
        }
        StoredEvent claimed = claim.get();
        String leaseOwner = claimed.leaseOwner() != null ? claimed.leaseOwner() : claimToken;
        if (claimed.payload() == null || claimed.payload().isBlank()) {
            stripeEventStore.markExhaustedReview(eventId, leaseOwner, "missing_payload");
            LOG.error("Stripe event {} has no stored payload; held for manual review", eventId);
            return false;
        }
        Event event;
        try {
            event = parseStoredPayload(eventId, claimed.payload());
        } catch (RuntimeException ex) {
            recordFailure(eventId, leaseOwner, claimed.attemptCount(), ex);
            return false;
        }
        try {
            process(event, event.getType() != null ? event.getType() : claimed.eventType());
            afterEffects(eventId);
            return stripeEventStore.markProcessed(eventId, leaseOwner, LocalDateTime.now(clock))
                    == StripeEventStore.InboxTransition.APPLIED;
        } catch (RuntimeException ex) {
            recordFailure(eventId, leaseOwner, claimed.attemptCount(), ex);
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

    private StripeEventStore.InboxTransition recordFailure(
            String eventId, String leaseOwner, int attemptCount, RuntimeException ex) {
        String failureClass = ex.getClass().getSimpleName();
        if (attemptCount >= maxAttempts) {
            StripeEventStore.InboxTransition outcome = stripeEventStore.markExhaustedReview(
                    eventId, leaseOwner, sanitizeCode("exhausted:" + failureClass));
            if (outcome == StripeEventStore.InboxTransition.APPLIED) {
                LOG.error("Stripe event {} exhausted {} attempts ({}); held for manual review",
                        eventId, attemptCount, failureClass);
            } else {
                LOG.warn("Stale Stripe worker could not park exhausted event {}", eventId);
            }
            return outcome;
        }
        long shift = Math.min(Math.max(attemptCount - 1, 0), 20);
        long backoff = Math.min(backoffBaseSeconds * (1L << shift), backoffMaxSeconds);
        LocalDateTime nextAttempt = LocalDateTime.now(clock).plusSeconds(backoff);
        StripeEventStore.InboxTransition outcome = stripeEventStore.markFailed(
                eventId, leaseOwner, sanitizeCode("effect_failed:" + failureClass), nextAttempt);
        if (outcome == StripeEventStore.InboxTransition.APPLIED) {
            LOG.warn("Stripe event {} processing failed ({}); retry {}/{} scheduled",
                    eventId, failureClass, attemptCount, maxAttempts);
        } else {
            LOG.warn("Stale Stripe worker could not schedule retry for event {}", eventId);
        }
        return outcome;
    }

    /**
     * Durable disposition of one verified webhook event. The inbox row may
     * become {@code PROCESSED} only after one of these commits:
     * <ul>
     *   <li>{@code APPLIED}: charge/failure/refund effects durably committed.</li>
     *   <li>{@code IDEMPOTENT}: same-charge replay converged without state change.</li>
     *   <li>{@code MANUAL_REVIEW}: conflicting provider evidence durably parked
     *   for an operator with sanitized linkage.</li>
     *   <li>{@code IGNORED_UNKNOWN}: no attempt maps to the verified charge; nothing to do.</li>
     *   <li>{@code UNSUPPORTED}: event type carries no payment effects.</li>
     * </ul>
     * Transient failures are never represented here: they propagate as
     * exceptions so the inbox parks for retry ({@code FAILED}) instead of
     * completing.
     */
    enum WebhookOutcome {
        APPLIED,
        IDEMPOTENT,
        MANUAL_REVIEW,
        IGNORED_UNKNOWN,
        UNSUPPORTED
    }

    /** Resolution of a verified charge to a stored attempt. */
    private sealed interface ChargeResolution permits ChargeResolution.Unknown,
            ChargeResolution.Reviewed, ChargeResolution.Mapped {
        record Unknown() implements ChargeResolution {
        }

        record Reviewed() implements ChargeResolution {
        }

        record Mapped(PaymentClaim claim) implements ChargeResolution {
        }
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

    private WebhookOutcome process(Event event, String eventType) {
        switch (eventType) {
            case "charge.succeeded" -> {
                return handleChargeSucceeded(chargeOf(event));
            }
            case "charge.failed" -> {
                return handleChargeFailed(chargeOf(event));
            }
            case "charge.refunded" -> {
                return handleChargeRefunded(chargeOf(event));
            }
            case "charge.dispute.created", "charge.dispute.funds_withdrawn", "charge.dispute.closed" -> {
                return handleDispute(event);
            }
            default -> {
                LOG.debug("Ignoring unhandled Stripe event type {}", eventType);
                return WebhookOutcome.UNSUPPORTED;
            }
        }
    }

    private WebhookOutcome handleChargeSucceeded(Charge charge) {
        if (charge == null || charge.getId() == null || charge.getId().isBlank()) {
            LOG.warn("Ignoring charge.succeeded without a provider charge id");
            return WebhookOutcome.UNSUPPORTED;
        }
        ChargeResolution resolution = resolveAttemptStrict(charge);
        if (resolution instanceof ChargeResolution.Unknown) {
            return WebhookOutcome.IGNORED_UNKNOWN;
        }
        if (resolution instanceof ChargeResolution.Reviewed) {
            return WebhookOutcome.MANUAL_REVIEW;
        }
        PaymentClaim claim = ((ChargeResolution.Mapped) resolution).claim();
        PaymentAttemptStatus state = claim.state();
        try {
            if (state == PaymentAttemptStatus.PROCESSING || state == PaymentAttemptStatus.CREATED) {
                // Trusted provider-event path: no worker token. Economics were
                // verified in resolveAttemptStrict and are re-verified with
                // checkout linkage inside the transition itself.
                paymentAttemptService.recordProviderChargeSuccess(
                        claim.attemptId(), charge.getId(), charge.getAmount(),
                        charge.getCurrency(), claim.checkoutId());
                // Charge is now durable; finalization converges idempotently.
                // A false return means finalization is pending for recovery,
                // not that the charge was lost.
                paymentFinalizationService.finalizeProviderOrder(
                        claim.attemptId(), claim.checkoutId(), charge.getId());
                return WebhookOutcome.APPLIED;
            }
            if (state == PaymentAttemptStatus.CHARGE_SUCCEEDED
                    || state == PaymentAttemptStatus.ORDER_FINALIZED) {
                if (!charge.getId().equals(claim.chargeId())) {
                    paymentAttemptService.preserveReviewEvidence(claim.attemptId(), charge.getId());
                    paymentAttemptService.markManualReview(
                            claim.attemptId(), "conflicting_provider_charge");
                    LOG.error("Conflicting charge for finalized attempt {}; held for manual review",
                            claim.attemptId());
                    return WebhookOutcome.MANUAL_REVIEW;
                }
                paymentFinalizationService.finalizeProviderOrder(
                        claim.attemptId(), claim.checkoutId(), charge.getId());
                return WebhookOutcome.IDEMPOTENT;
            }
            if (state == PaymentAttemptStatus.REFUNDED) {
                // Same charge (a different charge was already failed closed in
                // resolveAttemptStrict): preserve the refund, idempotent.
                try {
                    paymentAttemptService.recordProviderChargeSuccess(
                            claim.attemptId(), charge.getId(), charge.getAmount(),
                            charge.getCurrency(), claim.checkoutId());
                } catch (PaymentStateException ex) {
                    LOG.warn("Webhook charge.succeeded for refunded attempt {}: {}",
                            claim.attemptId(), ex.getMessage());
                    return WebhookOutcome.MANUAL_REVIEW;
                }
                return WebhookOutcome.IDEMPOTENT;
            }
            if (state == PaymentAttemptStatus.FAILED_FINAL) {
                // Contradictory success after a recorded no-charge terminal
                // state: never discard, never auto-fulfill, never touch stock.
                try {
                    paymentAttemptService.recordProviderChargeSuccess(
                            claim.attemptId(), charge.getId(), charge.getAmount(),
                            charge.getCurrency(), claim.checkoutId());
                } catch (PaymentStateException expected) {
                    LOG.error("Late success after failure for attempt {}; held for manual review",
                            claim.attemptId());
                    return WebhookOutcome.MANUAL_REVIEW;
                }
                LOG.error("Late success after failure for attempt {}; held for manual review",
                        claim.attemptId());
                return WebhookOutcome.MANUAL_REVIEW;
            }
            if (state == PaymentAttemptStatus.MANUAL_REVIEW) {
                paymentAttemptService.preserveReviewEvidence(claim.attemptId(), charge.getId());
                return WebhookOutcome.MANUAL_REVIEW;
            }
            // REFUND_PENDING and any other charged state: the service owns the
            // preserve-vs-review decision; a state rejection already committed
            // manual review.
            try {
                paymentAttemptService.recordProviderChargeSuccess(
                        claim.attemptId(), charge.getId(), charge.getAmount(),
                        charge.getCurrency(), claim.checkoutId());
            } catch (PaymentStateException ex) {
                LOG.warn("Webhook charge.succeeded could not be applied to attempt {}: {}",
                        claim.attemptId(), ex.getMessage());
                return WebhookOutcome.MANUAL_REVIEW;
            }
            return WebhookOutcome.IDEMPOTENT;
        } catch (PaymentStateException ex) {
            // State-machine rejection that did not already commit a review:
            // fail closed into review so contradictory evidence stays visible.
            try {
                paymentAttemptService.preserveReviewEvidence(claim.attemptId(), charge.getId());
                paymentAttemptService.markManualReview(claim.attemptId(), "provider_state_conflict");
            } catch (RuntimeException reviewError) {
                // Transient review failure must stay retryable, never processed.
                throw reviewError;
            }
            LOG.warn("Webhook charge.succeeded could not be applied to attempt {}: {}",
                    claim.attemptId(), ex.getMessage());
            return WebhookOutcome.MANUAL_REVIEW;
        }
    }

    private WebhookOutcome handleChargeFailed(Charge charge) {
        if (charge == null) {
            return WebhookOutcome.UNSUPPORTED;
        }
        ChargeResolution resolution = resolveAttemptStrict(charge);
        if (resolution instanceof ChargeResolution.Unknown) {
            return WebhookOutcome.IGNORED_UNKNOWN;
        }
        if (resolution instanceof ChargeResolution.Reviewed) {
            return WebhookOutcome.MANUAL_REVIEW;
        }
        PaymentClaim claim = ((ChargeResolution.Mapped) resolution).claim();
        PaymentAttemptStatus state = claim.state();
        if (state != PaymentAttemptStatus.PROCESSING && state != PaymentAttemptStatus.CREATED) {
            // Late failure must never regress a captured, finalized,
            // refunded, failed, or review state: idempotent no-op.
            return WebhookOutcome.IDEMPOTENT;
        }
        try {
            // Trusted provider-event path: no worker token. Forward-only and
            // never overwrites a durable charge.
            paymentAttemptService.recordProviderDecline(claim.attemptId(), charge.getFailureCode());
        } catch (PaymentStateException ex) {
            LOG.warn("Webhook charge.failed could not be applied to attempt {}: {}",
                    claim.attemptId(), ex.getMessage());
            return WebhookOutcome.IDEMPOTENT;
        }
        return WebhookOutcome.APPLIED;
    }

    private WebhookOutcome handleChargeRefunded(Charge charge) {
        if (charge == null) {
            return WebhookOutcome.UNSUPPORTED;
        }
        ChargeResolution resolution = resolveAttemptStrict(charge);
        if (resolution instanceof ChargeResolution.Unknown) {
            return WebhookOutcome.IGNORED_UNKNOWN;
        }
        if (resolution instanceof ChargeResolution.Reviewed) {
            return WebhookOutcome.MANUAL_REVIEW;
        }
        PaymentClaim claim = ((ChargeResolution.Mapped) resolution).claim();
        if (claim.state() == PaymentAttemptStatus.REFUNDED) {
            return WebhookOutcome.IDEMPOTENT;
        }
        if (claim.state() == PaymentAttemptStatus.MANUAL_REVIEW) {
            paymentAttemptService.preserveReviewEvidence(claim.attemptId(), charge.getId());
            return WebhookOutcome.MANUAL_REVIEW;
        }
        try {
            if (claim.state() == PaymentAttemptStatus.PROCESSING
                    || claim.state() == PaymentAttemptStatus.CREATED) {
                // A refund cannot arrive before the charge is recorded; keep the
                // state forward-only and let an operator reconcile.
                paymentAttemptService.markManualReview(claim.attemptId(), "refund_before_charge");
                return WebhookOutcome.MANUAL_REVIEW;
            }
            paymentAttemptService.recordRefunded(claim.attemptId(), charge.getId());
        } catch (PaymentStateException ex) {
            LOG.warn("Webhook charge.refunded could not be applied to attempt {}: {}",
                    claim.attemptId(), ex.getMessage());
            try {
                paymentAttemptService.preserveReviewEvidence(claim.attemptId(), charge.getId());
                paymentAttemptService.markManualReview(claim.attemptId(), "refund_state_conflict");
            } catch (RuntimeException reviewError) {
                throw reviewError;
            }
            return WebhookOutcome.MANUAL_REVIEW;
        }
        return WebhookOutcome.APPLIED;
    }

    private WebhookOutcome handleDispute(Event event) {
        StripeObject object = deserialize(event);
        if (!(object instanceof Dispute dispute) || dispute.getCharge() == null) {
            return WebhookOutcome.UNSUPPORTED;
        }
        PaymentClaim claim = paymentAttemptService.findByProviderCharge(dispute.getCharge());
        if (claim == null) {
            LOG.warn("Dispute for unknown charge {} could not be mapped to an attempt",
                    dispute.getCharge());
            return WebhookOutcome.IGNORED_UNKNOWN;
        }
        try {
            paymentAttemptService.markManualReview(claim.attemptId(), "stripe_dispute");
        } catch (PaymentStateException ex) {
            LOG.warn("Webhook dispute could not be applied to attempt {}: {}",
                    claim.attemptId(), ex.getMessage());
            return WebhookOutcome.MANUAL_REVIEW;
        }
        return WebhookOutcome.MANUAL_REVIEW;
    }

    /**
     * Strict resolution of a verified charge to a stored attempt. Economics
     * require a non-null exact amount and exact currency equality; checkout
     * metadata linkage must match the stored attempt; a different charge id
     * for the same checkout fails closed into manual review without touching
     * any other attempt.
     */
    private ChargeResolution resolveAttemptStrict(Charge charge) {
        String chargeId = charge.getId();
        String metadataCheckoutId = charge.getMetadata() == null
                ? null
                : charge.getMetadata().get(StripePaymentGatewayImpl.CHECKOUT_ID_METADATA_KEY);
        UUID metadataCheckout = null;
        if (metadataCheckoutId != null) {
            try {
                metadataCheckout = UUID.fromString(metadataCheckoutId);
            } catch (IllegalArgumentException ex) {
                LOG.warn("Stripe charge {} carries an unparsable checkout linkage", chargeId);
                return new ChargeResolution.Unknown();
            }
        }
        PaymentClaim byCharge = null;
        if (chargeId != null) {
            byCharge = paymentAttemptService.findByProviderCharge(chargeId);
        }
        PaymentClaim byCheckout = null;
        if (metadataCheckout != null) {
            byCheckout = paymentAttemptService.findByCheckoutId(metadataCheckout);
        }
        if (byCharge != null && byCheckout != null
                && !byCharge.attemptId().equals(byCheckout.attemptId())) {
            // The same checkout now claims a different charge, or the charge
            // belongs to another attempt: fail closed on the checkout attempt
            // without using the signed event to overwrite the other attempt.
            failClosedConflict(byCheckout.attemptId(), chargeId, "conflicting_provider_charge");
            return new ChargeResolution.Reviewed();
        }
        PaymentClaim claim = byCharge != null ? byCharge : byCheckout;
        if (claim == null) {
            LOG.warn("Stripe charge {} is not linked to a known attempt or checkout", chargeId);
            return new ChargeResolution.Unknown();
        }
        if (metadataCheckout != null && !metadataCheckout.equals(claim.checkoutId())) {
            failClosedConflict(claim.attemptId(), chargeId, "provider_checkout_mismatch");
            return new ChargeResolution.Reviewed();
        }
        if (!economicsMatch(claim, charge)) {
            LOG.error("Stripe event economics do not match attempt {}; moving to manual review",
                    claim.attemptId());
            failClosedConflict(claim.attemptId(), chargeId, "provider_amount_mismatch");
            return new ChargeResolution.Reviewed();
        }
        return new ChargeResolution.Mapped(claim);
    }

    private void failClosedConflict(UUID attemptId, String chargeId, String reason) {
        try {
            paymentAttemptService.preserveReviewEvidence(attemptId, chargeId);
        } catch (RuntimeException ex) {
            // Evidence attach is best-effort; the review transition below must
            // still commit. A transient failure here propagates as retryable.
            if (!(ex instanceof PaymentStateException)) {
                throw ex;
            }
        }
        paymentAttemptService.markManualReview(attemptId, reason);
    }

    /**
     * Exact economic equality: a missing/null amount or currency on either
     * side never counts as a match.
     */
    private boolean economicsMatch(PaymentClaim claim, Charge charge) {
        Long chargeAmount = charge.getAmount();
        String chargeCurrency = charge.getCurrency();
        if (chargeAmount == null || chargeCurrency == null || chargeCurrency.isBlank()) {
            return false;
        }
        String claimCurrency = claim.currency();
        if (claimCurrency == null || claimCurrency.isBlank()) {
            return false;
        }
        return chargeAmount.longValue() == claim.amountMinor()
                && claimCurrency.equalsIgnoreCase(chargeCurrency);
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
