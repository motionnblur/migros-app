package com.example.MigrosBackend.service.user.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Periodically releases stock reserved by checkouts that were never paid. The
 * per-checkout transition is idempotent, so a lazy expiration triggered by a
 * user request racing this job cannot double-release stock.
 */
@Component
public class CheckoutExpirationJob {

    private static final Logger LOG = LoggerFactory.getLogger(CheckoutExpirationJob.class);

    private final CheckoutService checkoutService;

    public CheckoutExpirationJob(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @Scheduled(
            fixedDelayString = "${payment.checkout.expiration-scan-ms:60000}",
            initialDelayString = "${payment.checkout.expiration-initial-delay-ms:60000}")
    public void releaseExpiredCheckouts() {
        List<UUID> expiredIds = checkoutService.findExpiredCheckoutIds();
        for (UUID checkoutId : expiredIds) {
            try {
                checkoutService.expireCheckout(checkoutId);
            } catch (RuntimeException ex) {
                LOG.warn("Failed to expire checkout {}", checkoutId, ex);
            }
        }
    }
}
