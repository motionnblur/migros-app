package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.entity.checkout.CheckoutEntity;
import com.example.MigrosBackend.entity.payment.PaymentAttemptEntity;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;
import com.example.MigrosBackend.exception.user.CheckoutNotFoundException;
import com.example.MigrosBackend.exception.user.PaymentAttemptNotFoundException;
import com.example.MigrosBackend.exception.user.PaymentStateException;
import com.example.MigrosBackend.exception.user.StalePaymentLeaseException;
import com.example.MigrosBackend.repository.user.CheckoutEntityRepository;
import com.example.MigrosBackend.repository.user.PaymentAttemptEntityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Retryable, idempotent local order finalization. The underlying checkout
 * finalization is transactional and guarded by a unique checkout-to-order
 * constraint, so calling this repeatedly for the same successful charge yields
 * exactly one order.
 *
 * <p>Two paths converge here:
 *
 * <ul>
 *   <li>Request-worker path ({@link #finalizeOrder(UUID, UUID, String, String)}):
 *   the worker must present the current attempt lease token; a stale token is
 *   rejected and reported as not finalized so the client keeps polling.</li>
 *   <li>Trusted provider path ({@link #finalizeProviderOrder(UUID, UUID, String)}):
 *   used by signature-verified webhooks, reconciliation, and status recovery
 *   after the charge is already durable. It performs no lease check but is
 *   forward-only and idempotent, so it can never regress or duplicate state.</li>
 * </ul>
 *
 * <p>Lock ordering is checkout first, then payment attempt, then checkout
 * items/product/order rows in every path (worker, provider, recovery, and
 * status-triggered finalization), so concurrent workers cannot deadlock.
 * Both methods run checkout/order and attempt changes in one transaction: the
 * lease fence is validated while holding both row locks before any
 * checkout/order mutation, so either all changes commit or none do.
 */
@Service
public class PaymentFinalizationService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentFinalizationService.class);

    private final CheckoutService checkoutService;
    private final PaymentAttemptService paymentAttemptService;
    private final CheckoutEntityRepository checkoutEntityRepository;
    private final PaymentAttemptEntityRepository paymentAttemptEntityRepository;

    public PaymentFinalizationService(CheckoutService checkoutService,
                                      PaymentAttemptService paymentAttemptService,
                                      CheckoutEntityRepository checkoutEntityRepository,
                                      PaymentAttemptEntityRepository paymentAttemptEntityRepository) {
        this.checkoutService = checkoutService;
        this.paymentAttemptService = paymentAttemptService;
        this.checkoutEntityRepository = checkoutEntityRepository;
        this.paymentAttemptEntityRepository = paymentAttemptEntityRepository;
    }

    /**
     * Request-worker finalization. The {@code leaseOwner} must be the current
     * attempt lease token; {@code null} or a replaced token never finalizes.
     * Returns false when the worker was fenced out (another worker owns the
     * attempt) or finalization is not yet possible — the caller must treat
     * this as pending, never as a decline, because money may have moved.
     *
     * <p>The lease fence (attempt id, {@code CHARGE_SUCCEEDED} state, provider
     * charge id, current lease token) is validated while holding the checkout
     * row lock first and then the attempt row lock, before any checkout/order
     * mutation. Checkout/order creation and the attempt transition commit in
     * the same transaction.
     */
    @Transactional
    public boolean finalizeOrder(UUID attemptId, UUID checkoutId, String chargeId, String leaseOwner) {
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new PaymentStateException("Order finalization requires the current lease token");
        }
        try {
            fenceWorker(attemptId, checkoutId, chargeId, leaseOwner);
            checkoutService.completePayment(checkoutId, chargeId);
            paymentAttemptService.markOrderFinalized(attemptId, leaseOwner);
            return true;
        } catch (StalePaymentLeaseException fencedOut) {
            LOG.warn("Order finalization fenced out for attempt {} checkout {}: another worker owns the lease",
                    attemptId, checkoutId);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return false;
        } catch (RuntimeException finalizationError) {
            LOG.error("Order finalization pending for attempt {} checkout {} charge {}: {}",
                    attemptId, checkoutId, chargeId, finalizationError.getClass().getSimpleName());
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return false;
        }
    }

    /**
     * Trusted provider finalization for webhooks, reconciliation, and status
     * recovery. No worker token is required because the charge is already
     * durably recorded with verified economics; both steps are idempotent so
     * concurrent workers converge on exactly one order.
     *
     * <p>Runs in one transaction with checkout-first locking: the attempt must
     * already be {@code CHARGE_SUCCEEDED} (or {@code ORDER_FINALIZED} for
     * idempotent redelivery) with a matching charge id before any
     * checkout/order mutation.
     */
    @Transactional
    public boolean finalizeProviderOrder(UUID attemptId, UUID checkoutId, String chargeId) {
        try {
            fenceProvider(attemptId, checkoutId, chargeId);
            checkoutService.completePayment(checkoutId, chargeId);
            paymentAttemptService.markProviderFinalized(attemptId, chargeId);
            return true;
        } catch (RuntimeException finalizationError) {
            LOG.error("Order finalization pending for attempt {} checkout {} charge {}: {}",
                    attemptId, checkoutId, chargeId, finalizationError.getClass().getSimpleName());
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return false;
        }
    }

    /**
     * Worker fence: locks checkout first, then the attempt, and validates the
     * attempt id, expected {@code CHARGE_SUCCEEDED} state (or
     * {@code ORDER_FINALIZED} for idempotent convergence), provider charge id,
     * attempt-checkout linkage, and current lease token before any mutation.
     * Fencing is by token ownership, not wall-clock expiry: an expired but
     * unreplaced token still completes; replacement is what fences the old
     * worker.
     */
    private void fenceWorker(UUID attemptId, UUID checkoutId, String chargeId, String leaseOwner) {
        CheckoutEntity checkout = checkoutEntityRepository.findByIdForUpdate(checkoutId)
                .orElseThrow(CheckoutNotFoundException::new);
        PaymentAttemptEntity attempt = paymentAttemptEntityRepository.findByIdForUpdate(attemptId)
                .orElseThrow(PaymentAttemptNotFoundException::new);
        if (!checkoutId.equals(attempt.getCheckoutId())) {
            throw new PaymentStateException("Payment attempt does not belong to the checkout");
        }
        if (attempt.getStatus() == PaymentAttemptStatus.ORDER_FINALIZED) {
            return;
        }
        if (attempt.getStatus() != PaymentAttemptStatus.CHARGE_SUCCEEDED) {
            throw new PaymentStateException(
                    "Cannot finalize an order from state " + attempt.getStatus());
        }
        requireCurrentLease(attempt, leaseOwner);
        requireChargeMatch(attempt, chargeId);
    }

    /**
     * Provider fence: locks checkout first, then the attempt, and validates
     * the attempt id, forward-only state, provider charge id, and
     * attempt-checkout linkage before any mutation. Takes no worker token.
     */
    private void fenceProvider(UUID attemptId, UUID checkoutId, String chargeId) {
        CheckoutEntity checkout = checkoutEntityRepository.findByIdForUpdate(checkoutId)
                .orElseThrow(CheckoutNotFoundException::new);
        PaymentAttemptEntity attempt = paymentAttemptEntityRepository.findByIdForUpdate(attemptId)
                .orElseThrow(PaymentAttemptNotFoundException::new);
        if (!checkoutId.equals(attempt.getCheckoutId())) {
            throw new PaymentStateException("Payment attempt does not belong to the checkout");
        }
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
    }

    private void requireCurrentLease(PaymentAttemptEntity attempt, String leaseOwner) {
        if (leaseOwner == null || leaseOwner.isBlank()
                || attempt.getLeaseOwner() == null
                || !constantTimeEquals(attempt.getLeaseOwner(), leaseOwner)) {
            throw new StalePaymentLeaseException(
                    "Stale payment lease: the attempt is owned by another worker");
        }
    }

    private void requireChargeMatch(PaymentAttemptEntity attempt, String chargeId) {
        if (chargeId == null || chargeId.isBlank()) {
            throw new PaymentStateException("Order finalization requires the provider charge id");
        }
        if (attempt.getStripeChargeId() != null && !chargeId.equals(attempt.getStripeChargeId())) {
            throw new PaymentStateException("Provider charge id does not match the recorded charge");
        }
    }

    private boolean constantTimeEquals(String stored, String presented) {
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
