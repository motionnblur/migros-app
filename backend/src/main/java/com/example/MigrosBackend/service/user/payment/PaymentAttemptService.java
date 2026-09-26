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
import com.example.MigrosBackend.repository.user.CheckoutEntityRepository;
import com.example.MigrosBackend.repository.user.PaymentAttemptEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Owns the durable payment-attempt state machine. Every method here is a short
 * database transaction; the Stripe network call is deliberately kept out of
 * these methods so a slow provider can never hold a database transaction or row
 * lock open.
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

    @Transactional
    public CheckoutStatusDto recordChargeSuccess(UUID attemptId, String leaseOwner, String chargeId) {
        if (chargeId == null || chargeId.isBlank()) {
            throw new PaymentStateException("A successful charge must have a provider charge id");
        }
        PaymentAttemptEntity attempt = paymentAttemptEntityRepository.findByIdForUpdate(attemptId)
                .orElseThrow(PaymentAttemptNotFoundException::new);

        if (attempt.getStripeChargeId() != null && !attempt.getStripeChargeId().equals(chargeId)) {
            reject(attempt, PaymentAttemptStatus.MANUAL_REVIEW,
                    "Conflicting provider charge ids for the same attempt");
        }

        PaymentAttemptStatus state = attempt.getStatus();
        if (state == PaymentAttemptStatus.CHARGE_SUCCEEDED || state == PaymentAttemptStatus.ORDER_FINALIZED) {
            if (attempt.getStripeChargeId() == null) {
                attempt.setStripeChargeId(chargeId);
            }
            clearLease(attempt);
            save(attempt);
            return toStatus(attempt);
        }
        if (state != PaymentAttemptStatus.PROCESSING) {
            throw new PaymentStateException("Cannot record a charge from state " + state);
        }

        attempt.setStripeChargeId(chargeId);
        attempt.setProviderStatus("succeeded");
        clearLease(attempt);
        transition(attempt, PaymentAttemptStatus.CHARGE_SUCCEEDED);
        save(attempt);
        return toStatus(attempt);
    }

    @Transactional
    public void markOrderFinalized(UUID attemptId, String leaseOwner) {
        PaymentAttemptEntity attempt = paymentAttemptEntityRepository.findByIdForUpdate(attemptId)
                .orElseThrow(PaymentAttemptNotFoundException::new);
        if (attempt.getStatus() == PaymentAttemptStatus.ORDER_FINALIZED) {
            clearLease(attempt);
            save(attempt);
            return;
        }
        if (attempt.getStatus() != PaymentAttemptStatus.CHARGE_SUCCEEDED) {
            throw new PaymentStateException(
                    "Cannot finalize an order from state " + attempt.getStatus());
        }
        clearLease(attempt);
        transition(attempt, PaymentAttemptStatus.ORDER_FINALIZED);
        save(attempt);
    }

    @Transactional
    public void recordDecline(UUID attemptId, String errorCode) {
        PaymentAttemptEntity attempt = paymentAttemptEntityRepository.findByIdForUpdate(attemptId)
                .orElseThrow(PaymentAttemptNotFoundException::new);
        if (attempt.getStatus() == PaymentAttemptStatus.FAILED_FINAL) {
            return;
        }
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
