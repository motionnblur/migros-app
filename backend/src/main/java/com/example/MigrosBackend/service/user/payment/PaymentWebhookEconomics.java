package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.PaymentClaim;
import com.stripe.model.Charge;

/** Pure comparison of a verified Stripe charge against its mapped attempt. */
final class PaymentWebhookEconomics {

    private PaymentWebhookEconomics() {
    }

    /**
     * Exact economic equality: a missing/null amount or currency on either
     * side never counts as a match.
     */
    static boolean matches(PaymentClaim claim, Charge charge) {
        Long chargeAmount = charge.getAmount();
        String chargeCurrency = charge.getCurrency();
        if (chargeAmount == null || chargeCurrency == null || chargeCurrency.isBlank()) {
            return false;
        }
        String claimCurrency = claim.currency();
        if (claimCurrency == null || claimCurrency.isBlank()) {
            return false;
        }
        return chargeAmount.longValue() == claim.amountMinor()
                && claimCurrency.equalsIgnoreCase(chargeCurrency);
    }
}
