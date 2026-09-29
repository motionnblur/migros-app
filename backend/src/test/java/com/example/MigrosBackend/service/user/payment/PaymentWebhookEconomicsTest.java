package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.PaymentClaim;
import com.example.MigrosBackend.dto.payment.PaymentClaimDecision;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;
import com.stripe.model.Charge;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentWebhookEconomicsTest {

    private PaymentClaim claim(long amountMinor, String currency) {
        return new PaymentClaim(PaymentClaimDecision.PROCEED, UUID.randomUUID(), UUID.randomUUID(),
                "checkout:test:charge-v1", amountMinor, currency, null, null,
                PaymentAttemptStatus.PROCESSING);
    }

    private Charge charge(Long amount, String currency) {
        Charge charge = new Charge();
        charge.setAmount(amount);
        charge.setCurrency(currency);
        return charge;
    }

    @Test
    void requiresExactMinorAmountAndCaseInsensitiveCurrency() {
        assertTrue(PaymentWebhookEconomics.matches(claim(1000L, "try"), charge(1000L, "TRY")));
        assertFalse(PaymentWebhookEconomics.matches(claim(1000L, "try"), charge(1001L, "try")));
        assertFalse(PaymentWebhookEconomics.matches(claim(1000L, "try"), charge(1000L, "usd")));
    }

    @Test
    void missingOrBlankAmountCurrencyDataNeverMatches() {
        assertFalse(PaymentWebhookEconomics.matches(claim(1000L, "try"), charge(null, "try")));
        assertFalse(PaymentWebhookEconomics.matches(claim(1000L, "try"), charge(1000L, null)));
        assertFalse(PaymentWebhookEconomics.matches(claim(1000L, "try"), charge(1000L, " ")));
        assertFalse(PaymentWebhookEconomics.matches(claim(1000L, null), charge(1000L, "try")));
        assertFalse(PaymentWebhookEconomics.matches(claim(1000L, " "), charge(1000L, "try")));
    }
}
