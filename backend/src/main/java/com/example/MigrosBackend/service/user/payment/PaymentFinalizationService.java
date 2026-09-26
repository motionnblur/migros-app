package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.exception.user.PaymentStateException;
import com.example.MigrosBackend.exception.user.StalePaymentLeaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 */
@Service
public class PaymentFinalizationService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentFinalizationService.class);

    private final CheckoutService checkoutService;
    private final PaymentAttemptService paymentAttemptService;

    public PaymentFinalizationService(CheckoutService checkoutService,
                                      PaymentAttemptService paymentAttemptService) {
        this.checkoutService = checkoutService;
        this.paymentAttemptService = paymentAttemptService;
    }

    /**
     * Request-worker finalization. The {@code leaseOwner} must be the current
     * attempt lease token; {@code null} or a replaced token never finalizes.
     * Returns false when the worker was fenced out (another worker owns the
     * attempt) or finalization is not yet possible — the caller must treat
     * this as pending, never as a decline, because money may have moved.
     */
    public boolean finalizeOrder(UUID attemptId, UUID checkoutId, String chargeId, String leaseOwner) {
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new PaymentStateException("Order finalization requires the current lease token");
        }
        try {
            checkoutService.completePayment(checkoutId, chargeId);
            paymentAttemptService.markOrderFinalized(attemptId, leaseOwner);
            return true;
        } catch (StalePaymentLeaseException fencedOut) {
            LOG.warn("Order finalization fenced out for attempt {} checkout {}: another worker owns the lease",
                    attemptId, checkoutId);
            return false;
        } catch (RuntimeException finalizationError) {
            LOG.error("Order finalization pending for attempt {} checkout {} charge {}: {}",
                    attemptId, checkoutId, chargeId, finalizationError.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Trusted provider finalization for webhooks, reconciliation, and status
     * recovery. No worker token is required because the charge is already
     * durably recorded with verified economics; both steps are idempotent so
     * concurrent workers converge on exactly one order.
     */
    public boolean finalizeProviderOrder(UUID attemptId, UUID checkoutId, String chargeId) {
        try {
            checkoutService.completePayment(checkoutId, chargeId);
            paymentAttemptService.markProviderFinalized(attemptId, chargeId);
            return true;
        } catch (RuntimeException finalizationError) {
            LOG.error("Order finalization pending for attempt {} checkout {} charge {}: {}",
                    attemptId, checkoutId, chargeId, finalizationError.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Backwards-compatible trusted converge path for internal callers that
     * act on an already-durable, provider-verified charge. Delegates to
     * {@link #finalizeProviderOrder}; request workers must use the
     * lease-fenced {@link #finalizeOrder(UUID, UUID, String, String)} instead.
     */
    public boolean finalizeOrder(UUID attemptId, UUID checkoutId, String chargeId) {
        return finalizeProviderOrder(attemptId, checkoutId, chargeId);
    }
}
