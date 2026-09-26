package com.example.MigrosBackend.controller.user.payment;

import com.example.MigrosBackend.service.user.payment.PaymentWebhookService;
import com.example.MigrosBackend.service.user.payment.StripeWebhookVerifier;
import com.stripe.model.Event;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Stripe webhook receiver. Authentication is the Stripe signature over the raw
 * body, never the normal user session cookie.
 */
@RestController
@RequestMapping("/payment/webhook")
public class StripeWebhookController {

    private final StripeWebhookVerifier verifier;
    private final PaymentWebhookService paymentWebhookService;

    public StripeWebhookController(StripeWebhookVerifier verifier,
                                   PaymentWebhookService paymentWebhookService) {
        this.verifier = verifier;
        this.paymentWebhookService = paymentWebhookService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> receive(
            @RequestBody String payload,
            @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        // Verifies the raw body against the configured secret; throws
        // WebhookSignatureException (HTTP 400) on a missing or invalid signature.
        Event event = verifier.verify(payload, signature);
        paymentWebhookService.handle(event);
        return ResponseEntity.ok(Map.of("received", true));
    }
}
