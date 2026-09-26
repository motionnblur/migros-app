package com.example.MigrosBackend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fails startup outside exact-local development when the Stripe webhook secret
 * is missing. Webhook reconciliation is a required part of payment durability,
 * so a non-local deployment must not silently run without it.
 */
@Component
public class StripeWebhookStartupValidation {

    private final AdminStartupProfilePolicy profilePolicy;
    private final String webhookSecret;

    public StripeWebhookStartupValidation(AdminStartupProfilePolicy profilePolicy,
                                          @Value("${payment.webhook.secret:}") String webhookSecret) {
        this.profilePolicy = profilePolicy;
        this.webhookSecret = webhookSecret;
    }

    @PostConstruct
    public void validate() {
        boolean missing = webhookSecret == null || webhookSecret.isBlank();
        if (missing && !profilePolicy.isLocalDevelopment()) {
            throw new IllegalStateException(
                    "STRIPE_WEBHOOK_SECRET must be configured outside exact-local development");
        }
    }
}
