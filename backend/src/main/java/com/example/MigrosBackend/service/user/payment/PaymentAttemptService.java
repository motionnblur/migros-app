package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.CheckoutPaymentStart;
import com.example.MigrosBackend.dto.payment.CheckoutStatusDto;
import com.example.MigrosBackend.dto.payment.PaymentClaim;
import com.example.MigrosBackend.dto.payment.PaymentClaimDecision;
import com.example.MigrosBackend.dto.payment.PaymentStatusDto;
import com.example.MigrosBackend.entity.checkout.CheckoutEntity;
import com.example.MigrosBackend.entity.checkout.CheckoutStatus;
import com.example.MigrosBackend.entity.payment.PaymentAttemptEntity;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.exception.user.CheckoutNotFoundException;
import com.example.MigrosBackend.exception.user.PaymentAttemptNotFoundException;
import com.example.MigrosBackend.exception.user.PaymentStateException;
import com.example.MigrosBackend.exception.user.StalePaymentLeaseException;
import com.example.MigrosBackend.repository.user.CheckoutEntityRepository;
import com.example.MigrosBackend.repository.user.PaymentAttemptEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Owns the durable payment-attempt state machine. Every method here is a short
 * database transaction; the Stripe network call is deliberately kept out of
 * these methods so a slow provider can never hold a database transaction or row
 * lock open.
 *
 * <p>Lease fencing: every provider call initiated by a request worker receives
 * an opaque lease token from {@link #claim}. Every worker result
 * ({@link #recordChargeSuccess}, {@link #recordDecline},
 * {@link #markOrderFinalized}) must present the same token, which is compared
 * in constant time against the stored owner while holding the pessimistic row
 * lock — never as a read-then-write sequence. Fencing is by token ownership,
 * not wall-clock expiry: a result that began before expiry still commits while
 * its token is current; once another claim replaces the token, every result
 * from the older worker is rejected with {@link StalePaymentLeaseException}
 * without changing attempt, checkout, order, stock, lease, or refund state.
 *
 * <p>Stripe webhooks and reconciliation never possess a worker token. They use
 * the separate provider-event methods
 * ({@link #recordProviderChargeSuccess}, {@link #recordProviderDecline},
 * {@link #markProviderFinalized}) which require full provider-economic
 * verification instead of a lease. Worker methods never accept {@code null} as
 * a fencing bypass for a state-changing transition.
 *
 * <p>Lock ordering is checkout first, then attempt, then product rows in every
 * path (claim, status, decline, finalization), so concurrent workers cannot
 * deadlock or strand a processing attempt.
 */
@Service
public class PaymentAttemptService {

    private final TokenService tokenService;
    private final UserEntityRepository userEntityRepository;
    private final CheckoutEntityRepository checkoutEntityRepository;
    private final PaymentAttemptEntityRepository paymentAttemptEntityRepository;
    private final CheckoutService checkoutService;
    private final long leaseSeconds;

    public PaymentAttemptService(TokenService tokenService,
                                 UserEntityRepository userEntityRepository,
                                 CheckoutEntityRepository checkoutEntityRepository,
                                 PaymentAttemptEntityRepository paymentAttemptEntityRepository,
                                 CheckoutService checkoutService,
                                 @Value("${payment.attempt.lease-seconds:120}") long leaseSeconds) {
        this.tokenService = tokenService;
        this.userEntityRepository = userEntityRepository;
        this.checkoutEntityRepository = checkoutEntityRepository;
        this.paymentAttemptEntityRepository = paymentAttemptEntityRepository;
        this.checkoutService = checkoutService;
        this.leaseSeconds = leaseSeconds;
    }

    /**
     * Claims the one attempt for a checkout. Locks the owned checkout first so
     * only one concurrent request can create/own the attempt at a time. A
     * still-valid processing lease returns PENDING; an expired lease is
     * reclaimed with the same idempotency key.
     */
    @Transactional
    public PaymentClaim claim(String userToken, UUID checkoutId, String idempotencyKey) {
        UserEntity user = authenticatedUser(userToken);
        CheckoutEntity checkout = checkoutEntityRepository
                .findOwnedByIdForUpdate(checkoutId, user.getId())
                .orElseThrow(CheckoutNotFoundException::new);

        PaymentAttemptEntity attempt = paymentAttemptEntityRepository
                .findByCheckoutIdForUpdate(checkoutId)
                .orElse(null);

        if (attempt == null) {
            requireCompatibleKey(idempotencyKey);
            CheckoutPaymentStart start = checkoutService.beginPayment(userToken, checkoutId);
            attempt = newAttempt(checkout, start, idempotencyKey);
        } else {
            if (!attempt.getIdempotencyKey().equals(idempotencyKey)) {
                throw new PaymentStateException("Idempotency key does not match the stored attempt");
            }
            verifySnapshot(attempt, checkout);
        }

        LocalDateTime now = LocalDateTime.now();
        PaymentAttemptStatus state = attempt.getStatus();

        if (state.isFinalized()) {
            return describe(attempt, PaymentClaimDecision.FINALIZED, null);
        }
        if (state == PaymentAttemptStatus.CHARGE_SUCCEEDED) {
            if (hasValidLease(attempt, now)) {
                // Another worker owns the finalization window; report FINALIZE
                // without stealing its lease.
                return describe(attempt, PaymentClaimDecision.FINALIZE, null);
            }
            String leaseOwner = acquireLease(attempt, now);
            save(attempt);
            return describe(attempt, PaymentClaimDecision.FINALIZE, leaseOwner);
        }
        if (state == PaymentAttemptStatus.PROCESSING) {
            if (hasValidLease(attempt, now)) {
                return describe(attempt, PaymentClaimDecision.PENDING, null);
            }
            String leaseOwner = acquireLease(attempt, now);
            save(attempt);
            return describe(attempt, PaymentClaimDecision.PROCEED, leaseOwner);
        }
        if (state == PaymentAttemptStatus.CREATED) {
            transition(attempt, PaymentAttemptStatus.PROCESSING);
            String leaseOwner = acquireLease(attempt, now);
            save(attempt);
            return describe(attempt, PaymentClaimDecision.PROCEED, leaseOwner);
        }
        return describe(attempt, PaymentClaimDecision.TERMINAL, null);
    }

    @Transactional
    public PaymentClaim describeAttempt(UUID attemptId) {
        PaymentAttemptEntity attempt = paymentAttemptEntityRepository.findByIdForUpdate(attemptId)
                .orElseThrow(PaymentAttemptNotFoundException::new);
        return describe(attempt, decisionFor(attempt.getStatus(), attempt, LocalDateTime.now()), null);
    }

    /**
     * Request-worker transition: records a provider success. Only the worker
     * holding the current lease token may move the attempt; a stale, null, or
     * blank token is rejected before any state change. The lease is kept (with
     * a refreshed expiry) across the success transition so the same token
     * guards order finalization.
     *
     * <p>A genuine charge-id conflict detected by the current owner fails
     * closed into manual review. The review transition is committed even
     * though the method still throws to signal failure, hence
     * {@code noRollbackFor}: every other throw happens before any mutation,
     * so there is nothing else to roll back.
     */
    @Transactional(noRollbackFor = PaymentStateException.class)
    public CheckoutStatusDto recordChargeSuccess(UUID attemptId, String leaseOwner, String chargeId) {
        if (chargeId == null || chargeId.isBlank()) {
            throw new PaymentStateException("A successful charge must have a provider charge id");
        }
        PaymentAttemptEntity attempt = paymentAttemptEntityRepository.findByIdForUpdate(attemptId)
                .orElseThrow(PaymentAttemptNotFoundException::new);

        PaymentAttemptStatus state = attempt.getStatus();
        if ((state == PaymentAttemptStatus.CHARGE_SUCCEEDED
                || state == PaymentAttemptStatus.ORDER_FINALIZED)
                && chargeId.equals(attempt.getStripeChargeId())) {
            // Idempotent replay of the already-durable success: no attempt,
            // lease, checkout, stock, or order change.
            return toStatus(attempt);
        }

        requireCurrentLease(attempt, leaseOwner);

        if (attempt.getStripeChargeId() != null && !attempt.getStripeChargeId().equals(chargeId)) {
            reject(attempt, PaymentAttemptStatus.MANUAL_REVIEW,
                    "Conflicting provider charge ids for the same attempt");
        }

        if (state != PaymentAttemptStatus.PROCESSING) {
            throw new PaymentStateException("Cannot record a charge from state " + state);
        }

        attempt.setStripeChargeId(chargeId);
        attempt.setProviderStatus("succeeded");
        transition(attempt, PaymentAttemptStatus.CHARGE_SUCCEEDED);
        extendLease(attempt, LocalDateTime.now());
        save(attempt);
        return toStatus(attempt);
    }

    /**
     * Trusted provider-event transition for signature-verified webhooks and
     * gateway reconciliation. Takes no worker token; instead it requires full
     * economic linkage (checkout id, exact amount and currency) plus a legal
     * forward-only transition. Any mismatch fails closed into manual review;
     * a conflicting provider charge id never overwrites durable state.
     *
     * <p>Reverse out-of-order policy for a verified success:
     * <ul>
     *   <li>{@code PROCESSING}/{@code CREATED} plus matching success: record
     *   the charge and finalize normally.</li>
     *   <li>{@code CHARGE_SUCCEEDED}/{@code ORDER_FINALIZED} plus the same
     *   charge: idempotent replay, history preserved.</li>
     *   <li>{@code REFUNDED} plus the same charge: preserve the refund state
     *   and complete as an idempotent historical success.</li>
     *   <li>{@code FAILED_FINAL} plus provider success: record a sanitized
     *   conflict and move to {@code MANUAL_REVIEW}; never silently discard,
     *   never auto-fulfill, never release stock again.</li>
     *   <li>{@code MANUAL_REVIEW}: preserve the review state and attach
     *   sanitized provider linkage without erasing the existing reason.</li>
     *   <li>A different charge id for the same checkout always fails closed
     *   into manual review without overwriting the canonical charge id.</li>
     * </ul>
     *
     * <p>Like the worker success path, the manual-review transition is
     * committed even though the method throws to signal failure, hence
     * {@code noRollbackFor}; every other throw happens before any mutation.
     */
    @Transactional(noRollbackFor = PaymentStateException.class)
    public CheckoutStatusDto recordProviderChargeSuccess(UUID attemptId, String chargeId,
                                                         Long amountMinor, String currency,
                                                         UUID checkoutId) {
        if (chargeId == null || chargeId.isBlank()) {
            throw new PaymentStateException("A successful charge must have a provider charge id");
        }
        if (amountMinor == null || currency == null || currency.isBlank() || checkoutId == null) {
            throw new PaymentStateException("Provider charge evidence is incomplete");
        }
        PaymentAttemptEntity attempt = paymentAttemptEntityRepository.findByIdForUpdate(attemptId)
                .orElseThrow(PaymentAttemptNotFoundException::new);

        if (attempt.getStatus() == PaymentAttemptStatus.MANUAL_REVIEW) {
            // Already parked for operator review: preserve the existing review
            // reason, canonical charge id, status, and lease; only attach
            // sanitized provider linkage for reconciliation. Must run before
            // any conflicting-charge branch that calls reject(...), otherwise
            // the original errorCode would be overwritten.
            attachConflictEvidence(attempt, chargeId);
            save(attempt);
            return toStatus(attempt);
        }
        if (!checkoutId.equals(attempt.getCheckoutId())) {
            reject(attempt, PaymentAttemptStatus.MANUAL_REVIEW,
                    "Provider charge checkout linkage does not match the attempt");
        }
        if (!amountMinor.equals(attempt.getAmountMinor())
                || !currency.equalsIgnoreCase(attempt.getCurrency())) {
            reject(attempt, PaymentAttemptStatus.MANUAL_REVIEW,
                    "Provider charge amount or currency does not match the attempt");
        }
        if (attempt.getStripeChargeId() != null && !attempt.getStripeChargeId().equals(chargeId)) {
            attachConflictEvidence(attempt, chargeId);
            reject(attempt, PaymentAttemptStatus.MANUAL_REVIEW,
                    "Conflicting provider charge ids for the same attempt");
        }

        PaymentAttemptStatus state = attempt.getStatus();
        if (state == PaymentAttemptStatus.CHARGE_SUCCEEDED
                || state == PaymentAttemptStatus.ORDER_FINALIZED) {
            if (attempt.getStripeChargeId() == null) {
                attempt.setStripeChargeId(chargeId);
            }
            if (attempt.getProviderStatus() == null) {
                attempt.setProviderStatus("succeeded");
            }
            save(attempt);
            return toStatus(attempt);
        }
        if (state == PaymentAttemptStatus.REFUNDED) {
            // Historical success for an already-refunded charge: preserve the
            // refund state (refund id, status) and complete idempotently. A
            // different charge id was already rejected above, so reaching here
            // means the same charge or no canonical charge yet.
            if (attempt.getStripeChargeId() == null) {
                attempt.setStripeChargeId(chargeId);
            }
            if (attempt.getProviderStatus() == null) {
                attempt.setProviderStatus("succeeded");
            }
            save(attempt);
            return toStatus(attempt);
        }
        if (state == PaymentAttemptStatus.FAILED_FINAL) {
            // Contradictory provider evidence: money may have moved after the
            // application released stock and cancelled the checkout. Record a
            // sanitized conflict and surface for manual review. Never record
            // as a charge, never fulfill, never release stock again.
            if (attempt.getStripeChargeId() == null) {
                attempt.setStripeChargeId(chargeId);
                attempt.setProviderStatus("succeeded");
            } else {
                attachConflictEvidence(attempt, chargeId);
            }
            reject(attempt, PaymentAttemptStatus.MANUAL_REVIEW,
                    "late_success_after_failure");
        }
        if (state == PaymentAttemptStatus.REFUND_PENDING) {
            // A durable charge already exists and a refund is in flight: the
            // same charge is an idempotent preserve, anything else was already
            // rejected above as a conflict.
            if (attempt.getProviderStatus() == null) {
                attempt.setProviderStatus("succeeded");
            }
            save(attempt);
            return toStatus(attempt);
        }
        if (state != PaymentAttemptStatus.PROCESSING && state != PaymentAttemptStatus.CREATED) {
            throw new PaymentStateException("Cannot record a provider charge from state " + state);
        }

        attempt.setStripeChargeId(chargeId);
        attempt.setProviderStatus("succeeded");
        clearLease(attempt);
        transition(attempt, PaymentAttemptStatus.CHARGE_SUCCEEDED);
        save(attempt);
        return toStatus(attempt);
    }

    /**
     * Request-worker transition: marks the order finalized. Only the current
     * lease holder may complete finalization; a stale token is rejected
     * without touching attempt, order, or lease state. Locks are acquired
     * checkout-first to match the claim path.
     */
    @Transactional
    public void markOrderFinalized(UUID attemptId, String leaseOwner) {
        PaymentAttemptEntity attempt = lockAttemptWithCheckoutFirst(attemptId);
        if (attempt.getStatus() == PaymentAttemptStatus.ORDER_FINALIZED) {
            return;
        }
        requireCurrentLease(attempt, leaseOwner);
        if (attempt.getStatus() != PaymentAttemptStatus.CHARGE_SUCCEEDED) {
            throw new PaymentStateException(
                    "Cannot finalize an order from state " + attempt.getStatus());
        }
        clearLease(attempt);
        transition(attempt, PaymentAttemptStatus.ORDER_FINALIZED);
        save(attempt);
    }

    /**
     * Trusted provider-event transition for verified webhooks and recovery:
     * converges an already-charged attempt to finalized without a worker
     * token. Forward-only — it can never regress or overwrite newer durable
     * state — and it fails closed when the charge id disagrees with the
     * recorded charge. Locks are acquired checkout-first to match the claim
     * path.
     */
    @Transactional
    public void markProviderFinalized(UUID attemptId, String chargeId) {
        PaymentAttemptEntity attempt = lockAttemptWithCheckoutFirst(attemptId);
        if (attempt.getStatus() == PaymentAttemptStatus.ORDER_FINALIZED) {
            return;
        }
        if (attempt.getStatus() != PaymentAttemptStatus.CHARGE_SUCCEEDED) {
            throw new PaymentStateException(
                    "Cannot finalize an order from state " + attempt.getStatus());
        }
        if (chargeId != null && attempt.getStripeChargeId() != null
                && !chargeId.equals(attempt.getStripeChargeId())) {
            throw new PaymentStateException("Provider charge id does not match the recorded charge");
        }
        if (attempt.getStripeChargeId() == null && chargeId != null) {
            attempt.setStripeChargeId(chargeId);
        }
        clearLease(attempt);
        transition(attempt, PaymentAttemptStatus.ORDER_FINALIZED);
        save(attempt);
    }

    /**
     * Request-worker transition: records a provider-confirmed decline. Fencing
     * is verified before the terminal transition, and the checkout release
     * commits in the same database transaction. A stale token changes nothing:
     * no attempt transition, no lease clearing, no checkout cancellation, no
     * stock release. Locks are acquired checkout-first to match the claim path.
     */
    @Transactional
    public void recordDecline(UUID attemptId, String leaseOwner, String errorCode) {
        PaymentAttemptEntity attempt = lockAttemptWithCheckoutFirst(attemptId);
        if (attempt.getStatus() == PaymentAttemptStatus.FAILED_FINAL) {
            return;
        }
        requireCurrentLease(attempt, leaseOwner);
        if (attempt.getStatus() != PaymentAttemptStatus.PROCESSING
                && attempt.getStatus() != PaymentAttemptStatus.CREATED) {
            // A charge may already exist; never overwrite a durable success.
            throw new PaymentStateException(
                    "Cannot record a decline from state " + attempt.getStatus());
        }
        attempt.setErrorCode(sanitizeCode(errorCode));
        clearLease(attempt);
        transition(attempt, PaymentAttemptStatus.FAILED_FINAL);
        save(attempt);
        checkoutService.failPayment(attempt.getCheckoutId());
    }

    /**
     * Trusted provider-event transition for verified failure events. Takes no
     * worker token; the attempt must be in a non-charged state and the
     * checkout release commits in the same transaction. Never overwrites a
     * durable charge. Locks are acquired checkout-first to match the claim
     * path.
     */
    @Transactional
    public void recordProviderDecline(UUID attemptId, String errorCode) {
        PaymentAttemptEntity attempt = lockAttemptWithCheckoutFirst(attemptId);
        if (attempt.getStatus() == PaymentAttemptStatus.FAILED_FINAL) {
            return;
        }
        if (attempt.getStatus() != PaymentAttemptStatus.PROCESSING
                && attempt.getStatus() != PaymentAttemptStatus.CREATED) {
            // A charge may already exist; never overwrite a durable success.
            throw new PaymentStateException(
                    "Cannot record a provider decline from state " + attempt.getStatus());
        }
        attempt.setErrorCode(sanitizeCode(errorCode));
        clearLease(attempt);
        transition(attempt, PaymentAttemptStatus.FAILED_FINAL);
        save(attempt);
        checkoutService.failPayment(attempt.getCheckoutId());
    }

    @Transactional(readOnly = true)
    public PaymentClaim findByProviderCharge(String chargeId) {
        if (chargeId == null || chargeId.isBlank()) {
            return null;
        }
        return paymentAttemptEntityRepository.findByStripeChargeId(chargeId)
                .map(attempt -> describe(attempt, PaymentClaimDecision.PENDING, null))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public PaymentClaim findByCheckoutId(UUID checkoutId) {
        if (checkoutId == null) {
            return null;
        }
        return paymentAttemptEntityRepository.findByCheckoutId(checkoutId)
                .map(attempt -> describe(attempt, PaymentClaimDecision.PENDING, null))
                .orElse(null);
    }

    @Transactional
    public void markManualReview(UUID attemptId, String reason) {
        PaymentAttemptEntity attempt = paymentAttemptEntityRepository.findByIdForUpdate(attemptId)
                .orElseThrow(PaymentAttemptNotFoundException::new);
        if (attempt.getStatus() == PaymentAttemptStatus.MANUAL_REVIEW) {
            return;
        }
        attempt.setErrorCode(sanitizeCode(reason));
        clearLease(attempt);
        transition(attempt, PaymentAttemptStatus.MANUAL_REVIEW);
        save(attempt);
    }

    /**
     * Attaches sanitized provider linkage to an attempt already in
     * {@code MANUAL_REVIEW} without erasing the existing review reason.
     * When the canonical charge id is still empty the late charge is stored
     * as the linkage; when it differs the conflict is recorded in
     * {@code provider_status} (sanitized, length-bounded) so the canonical id
     * is never overwritten. Never changes status, error code, lease, order,
     * or stock state.
     */
    @Transactional
    public void preserveReviewEvidence(UUID attemptId, String chargeId) {
        if (chargeId == null || chargeId.isBlank()) {
            return;
        }
        PaymentAttemptEntity attempt = paymentAttemptEntityRepository.findByIdForUpdate(attemptId)
                .orElseThrow(PaymentAttemptNotFoundException::new);
        boolean changed = attachConflictEvidence(attempt, chargeId);
        if (changed) {
            attempt.setUpdatedAt(LocalDateTime.now());
            save(attempt);
        }
    }

    @Transactional
    public boolean markRefundPending(UUID attemptId, String reason) {
        PaymentAttemptEntity attempt = paymentAttemptEntityRepository.findByIdForUpdate(attemptId)
                .orElseThrow(PaymentAttemptNotFoundException::new);
        if (attempt.getStatus() == PaymentAttemptStatus.REFUNDED) {
            return false;
        }
        if (attempt.getStatus() == PaymentAttemptStatus.REFUND_PENDING) {
            return true;
        }
        if (attempt.getStripeChargeId() == null || !attempt.getStatus().hasDurableCharge()) {
            throw new PaymentStateException("Cannot refund an attempt without a captured charge");
        }
        attempt.setErrorCode(sanitizeCode(reason));
        clearLease(attempt);
        transition(attempt, PaymentAttemptStatus.REFUND_PENDING);
        save(attempt);
        return true;
    }

    @Transactional
    public void recordRefunded(UUID attemptId, String refundId) {
        PaymentAttemptEntity attempt = paymentAttemptEntityRepository.findByIdForUpdate(attemptId)
                .orElseThrow(PaymentAttemptNotFoundException::new);
        if (attempt.getStatus() == PaymentAttemptStatus.REFUNDED) {
            return;
        }
        if (attempt.getStatus() != PaymentAttemptStatus.REFUND_PENDING
                && attempt.getStatus() != PaymentAttemptStatus.ORDER_FINALIZED
                && attempt.getStatus() != PaymentAttemptStatus.CHARGE_SUCCEEDED
                && attempt.getStatus() != PaymentAttemptStatus.MANUAL_REVIEW) {
            throw new PaymentStateException(
                    "Cannot record a refund from state " + attempt.getStatus());
        }
        attempt.setRefundId(refundId);
        clearLease(attempt);
        transition(attempt, PaymentAttemptStatus.REFUNDED);
        save(attempt);
    }

    @Transactional
    public PaymentStatusDto getStatus(String userToken, UUID checkoutId) {
        UserEntity user = authenticatedUser(userToken);
        CheckoutEntity checkout = checkoutEntityRepository
                .findOwnedByIdForUpdate(checkoutId, user.getId())
                .orElseThrow(CheckoutNotFoundException::new);

        CheckoutStatusDto checkoutStatus = checkoutService.getCheckout(userToken, checkoutId);
        PaymentAttemptEntity attempt = paymentAttemptEntityRepository
                .findByCheckoutIdForUpdate(checkoutId)
                .orElse(null);

        if (attempt == null) {
            CheckoutStatus checkoutState = checkout.getStatus();
            return new PaymentStatusDto(
                    checkoutId.toString(), null, checkoutState.name(), null,
                    checkout.getStripeChargeId(), checkout.getTotalAmount(), checkout.getAmountMinor(),
                    checkout.getCurrency(), checkout.getOrderGroupEntityId(),
                    checkoutState == CheckoutStatus.CONSUMED, false, false);
        }

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

    @Transactional(readOnly = true)
    public PaymentClaim describeForRecovery(UUID attemptId) {
        PaymentAttemptEntity attempt = paymentAttemptEntityRepository.findById(attemptId).orElse(null);
        if (attempt == null) {
            return null;
        }
        return describe(attempt, decisionFor(attempt.getStatus(), attempt, LocalDateTime.now()), null);
    }

    @Transactional(readOnly = true)
    public List<UUID> findStaleAttemptIds(List<PaymentAttemptStatus> statuses, LocalDateTime before) {
        return paymentAttemptEntityRepository.findStaleIds(statuses, before);
    }

    private PaymentClaimDecision decisionFor(PaymentAttemptStatus state,
                                             PaymentAttemptEntity attempt,
                                             LocalDateTime now) {
        return switch (state) {
            case ORDER_FINALIZED, REFUNDED -> PaymentClaimDecision.FINALIZED;
            case CHARGE_SUCCEEDED -> PaymentClaimDecision.FINALIZE;
            case PROCESSING -> hasValidLease(attempt, now)
                    ? PaymentClaimDecision.PENDING
                    : PaymentClaimDecision.PROCEED;
            case CREATED -> PaymentClaimDecision.PROCEED;
            default -> PaymentClaimDecision.TERMINAL;
        };
    }

    private PaymentAttemptEntity newAttempt(CheckoutEntity checkout,
                                            CheckoutPaymentStart start,
                                            String idempotencyKey) {
        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setId(UUID.randomUUID());
        attempt.setCheckout(checkout);
        attempt.setIdempotencyKey(idempotencyKey);
        attempt.setAmountMinor(start.amountMinor());
        attempt.setCurrency(start.currency());
        attempt.setStatus(PaymentAttemptStatus.CREATED);
        attempt.setCreatedAt(LocalDateTime.now());
        attempt.setUpdatedAt(LocalDateTime.now());
        return paymentAttemptEntityRepository.saveAndFlush(attempt);
    }

    private void verifySnapshot(PaymentAttemptEntity attempt, CheckoutEntity checkout) {
        if (!attempt.getAmountMinor().equals(checkout.getAmountMinor())
                || !attempt.getCurrency().equals(checkout.getCurrency())) {
            throw new PaymentStateException(
                    "Stored attempt amount/currency does not match the immutable checkout");
        }
    }

    private void requireCompatibleKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new GeneralException("A server-derived idempotency key is required");
        }
    }

    private String acquireLease(PaymentAttemptEntity attempt, LocalDateTime now) {
        String owner = UUID.randomUUID().toString();
        attempt.setLeaseOwner(owner);
        attempt.setLeaseExpiresAt(now.plusSeconds(leaseSeconds));
        attempt.setUpdatedAt(now);
        return owner;
    }

    /**
     * Extends the expiry of the currently held lease without rotating the
     * token, so the worker that just recorded success keeps authority over the
     * finalization window.
     */
    private void extendLease(PaymentAttemptEntity attempt, LocalDateTime now) {
        attempt.setLeaseExpiresAt(now.plusSeconds(leaseSeconds));
        attempt.setUpdatedAt(now);
    }

    /**
     * Fencing check run inside the row-locked transaction before any worker
     * state change. Ownership is by token equality only — an expired but
     * unreplaced token still commits — because replacement (not the wall
     * clock) is what revokes a worker. Comparison is constant-time so lease
     * tokens are not subject to timing probing.
     */
    private void requireCurrentLease(PaymentAttemptEntity attempt, String leaseOwner) {
        if (leaseOwner == null || leaseOwner.isBlank()
                || attempt.getLeaseOwner() == null
                || !constantTimeEquals(attempt.getLeaseOwner(), leaseOwner)) {
            throw new StalePaymentLeaseException(
                    "Stale payment lease: the attempt is owned by another worker");
        }
    }

    private boolean constantTimeEquals(String stored, String presented) {
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Loads the attempt for a decline path with locks acquired checkout-first,
     * matching the claim path order (checkout, then attempt, then product
     * rows). The initial non-locking read only determines which checkout row
     * to lock; all fencing and state decisions happen after both row locks
     * are held.
     */
    private PaymentAttemptEntity lockAttemptWithCheckoutFirst(UUID attemptId) {
        PaymentAttemptEntity probe = paymentAttemptEntityRepository.findById(attemptId)
                .orElseThrow(PaymentAttemptNotFoundException::new);
        UUID checkoutId = probe.getCheckoutId();
        if (checkoutId != null) {
            checkoutEntityRepository.findByIdForUpdate(checkoutId);
        }
        return paymentAttemptEntityRepository.findByIdForUpdate(attemptId)
                .orElseThrow(PaymentAttemptNotFoundException::new);
    }

    private boolean hasValidLease(PaymentAttemptEntity attempt, LocalDateTime now) {
        return attempt.getLeaseOwner() != null
                && attempt.getLeaseExpiresAt() != null
                && attempt.getLeaseExpiresAt().isAfter(now);
    }

    private void clearLease(PaymentAttemptEntity attempt) {
        attempt.setLeaseOwner(null);
        attempt.setLeaseExpiresAt(null);
        attempt.setUpdatedAt(LocalDateTime.now());
    }

    private void transition(PaymentAttemptEntity attempt, PaymentAttemptStatus next) {
        if (!attempt.getStatus().canTransitionTo(next)) {
            throw new PaymentStateException(
                    "Illegal payment transition " + attempt.getStatus() + " -> " + next);
        }
        attempt.setStatus(next);
        attempt.setUpdatedAt(LocalDateTime.now());
    }

    private void reject(PaymentAttemptEntity attempt, PaymentAttemptStatus next, String reason) {
        attempt.setErrorCode(sanitizeCode(reason));
        clearLease(attempt);
        transition(attempt, next);
        save(attempt);
        throw new PaymentStateException(reason);
    }

    /**
     * Records sanitized provider linkage without overwriting the canonical
     * charge id. Returns true when the entity was mutated.
     */
    private boolean attachConflictEvidence(PaymentAttemptEntity attempt, String chargeId) {
        if (chargeId == null || chargeId.isBlank()) {
            return false;
        }
        String stored = attempt.getStripeChargeId();
        if (stored == null || stored.isBlank()) {
            attempt.setStripeChargeId(chargeId);
            if (attempt.getProviderStatus() == null) {
                attempt.setProviderStatus("succeeded");
            }
            return true;
        }
        if (stored.equals(chargeId)) {
            if (attempt.getProviderStatus() == null) {
                attempt.setProviderStatus("succeeded");
                return true;
            }
            return false;
        }
        String conflict = sanitizeCode("conflict:" + chargeId.trim());
        if (!conflict.equals(attempt.getProviderStatus())) {
            attempt.setProviderStatus(conflict);
            return true;
        }
        return false;
    }

    private void save(PaymentAttemptEntity attempt) {
        paymentAttemptEntityRepository.save(attempt);
    }

    private PaymentClaim describe(PaymentAttemptEntity attempt,
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

    private CheckoutStatusDto toStatus(PaymentAttemptEntity attempt) {
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

    private String sanitizeCode(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= 64 ? trimmed : trimmed.substring(0, 64);
    }

    private UserEntity authenticatedUser(String userToken) {
        String userMail = tokenService.validateAndExtractUser(userToken);
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        if (user == null) {
            throw new UserNotFoundException(userMail);
        }
        return user;
    }
}
