package com.example.MigrosBackend.service.user.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Retryable, idempotent local order finalization. The underlying checkout
 * finalization is transactional and guarded by a unique checkout-to-order
 * constraint, so calling this repeatedly for the same successful charge yields
 * exactly one order.
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

    public boolean finalizeOrder(UUID attemptId, UUID checkoutId, String chargeId) {
        try {
            checkoutService.completePayment(checkoutId, chargeId);
            paymentAttemptService.markOrderFinalized(attemptId, null);
            return true;
        } catch (RuntimeException finalizationError) {
            LOG.error("Order finalization pending for attempt {} checkout {} charge {}: {}",
                    attemptId, checkoutId, chargeId, finalizationError.getClass().getSimpleName());
            return false;
        }
    }
}
