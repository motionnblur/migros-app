package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.exception.user.WebhookSignatureException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verifies Stripe webhook signatures against the raw request body. Verification
 * is always fail-closed: a missing secret rejects the event rather than
 * accepting an unsigned payload.
 */
@Component
public class StripeWebhookVerifier {

    private final String webhookSecret;

    public StripeWebhookVerifier(@Value("${payment.webhook.secret:}") String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public Event verify(String payload, String signatureHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new WebhookSignatureException("Stripe webhook secret is not configured");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new WebhookSignatureException("Missing Stripe signature header");
        }
        try {
            return Webhook.constructEvent(payload, signatureHeader, webhookSecret);
        } catch (SignatureVerificationException ex) {
            throw new WebhookSignatureException("Invalid Stripe webhook signature");
        }
    }

    public boolean isConfigured() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }
}
