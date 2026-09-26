package com.example.MigrosBackend.service.user.payment;

import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.param.ChargeCreateParams;
import org.springframework.stereotype.Service;

@Service
public class StripePaymentGatewayImpl implements StripePaymentGateway {
    @Override
    public Charge charge(String sourceToken, long amountMinor, String currency) throws StripeException {
        ChargeCreateParams params = ChargeCreateParams.builder()
                .setAmount(amountMinor)
                .setCurrency(currency)
                .setDescription("Example charge")
                .setSource(sourceToken)
                .build();

        return Charge.create(params);
    }
}
