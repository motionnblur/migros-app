package com.example.MigrosBackend.service.user.payment;

import com.stripe.exception.StripeException;
import com.stripe.model.Charge;

public interface StripePaymentGateway {
    Charge charge(String sourceToken, long amountMinor, String currency) throws StripeException;
}
