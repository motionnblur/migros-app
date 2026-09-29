package com.example.MigrosBackend.service.user.payment;

import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.ChargeSearchResult;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.ChargeCreateParams;
import com.stripe.param.ChargeSearchParams;
import com.stripe.param.RefundCreateParams;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class StripePaymentGatewayImpl implements StripePaymentGateway {

    @Override
    public Charge charge(String sourceToken, long amountMinor, String currency,
                         String idempotencyKey, String checkoutId) throws StripeException {
        ChargeCreateParams params = ChargeCreateParams.builder()
                .setAmount(amountMinor)
                .setCurrency(currency)
                .setDescription("Migros order " + checkoutId)
                .setSource(sourceToken)
                .putMetadata(StripePaymentGateway.CHECKOUT_ID_METADATA_KEY, checkoutId)
                .build();

        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey(idempotencyKey)
                .build();

        return Charge.create(params, options);
    }

    @Override
    public Optional<Charge> findChargeForCheckout(String checkoutId) throws StripeException {
        if (checkoutId == null || checkoutId.isBlank()) {
            return Optional.empty();
        }
        String sanitized = checkoutId.replace("'", "");
        ChargeSearchParams params = ChargeSearchParams.builder()
                .setQuery("metadata['" + StripePaymentGateway.CHECKOUT_ID_METADATA_KEY
                        + "']:'" + sanitized + "'")
                .setLimit(1L)
                .build();

        ChargeSearchResult result = Charge.search(params);
        if (result == null || result.getData() == null || result.getData().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(result.getData().get(0));
    }

    @Override
    public Refund refund(String chargeId, String idempotencyKey) throws StripeException {
        RefundCreateParams params = RefundCreateParams.builder()
                .setCharge(chargeId)
                .build();

        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey(idempotencyKey)
                .build();

        return Refund.create(params, options);
    }
}
