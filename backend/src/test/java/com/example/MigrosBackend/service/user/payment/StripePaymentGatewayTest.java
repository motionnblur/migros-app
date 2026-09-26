package com.example.MigrosBackend.service.user.payment;

import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.ChargeSearchResult;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.ChargeCreateParams;
import com.stripe.param.ChargeSearchParams;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class StripePaymentGatewayTest {
    private final StripePaymentGatewayImpl gateway = new StripePaymentGatewayImpl();

    @Test
    void sendsMinorUnitsCurrencyMetadataAndIdempotencyKeyToStripe() throws StripeException {
        ArgumentCaptor<ChargeCreateParams> paramsCaptor = ArgumentCaptor.forClass(ChargeCreateParams.class);
        ArgumentCaptor<RequestOptions> optionsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
        Charge charge = mock(Charge.class);

        try (MockedStatic<Charge> mockedCharge = mockStatic(Charge.class)) {
            mockedCharge.when(() -> Charge.create(any(ChargeCreateParams.class), any(RequestOptions.class)))
                    .thenReturn(charge);

            Charge result = gateway.charge("tok_visa", 5000L, "try", "checkout:abc:charge-v1", "abc");

            assertSame(charge, result);
            mockedCharge.verify(() -> Charge.create(paramsCaptor.capture(), optionsCaptor.capture()));
        }

        assertEquals(5000L, paramsCaptor.getValue().getAmount());
        assertEquals("try", paramsCaptor.getValue().getCurrency());
        assertEquals("tok_visa", paramsCaptor.getValue().getSource());
        assertTrue(String.valueOf(paramsCaptor.getValue().getMetadata()).contains("checkout_id"));
        assertTrue(String.valueOf(paramsCaptor.getValue().getMetadata()).contains("abc"));
        assertEquals("checkout:abc:charge-v1", optionsCaptor.getValue().getIdempotencyKey());
    }

    @Test
    void looksUpChargesByCheckoutMetadataForReconciliation() throws StripeException {
        Charge charge = mock(Charge.class);
        when(charge.getId()).thenReturn("ch_found");
        ChargeSearchResult searchResult = mock(ChargeSearchResult.class);
        when(searchResult.getData()).thenReturn(List.of(charge));

        try (MockedStatic<Charge> mockedCharge = mockStatic(Charge.class)) {
            mockedCharge.when(() -> Charge.search(any(ChargeSearchParams.class))).thenReturn(searchResult);

            assertSame(charge, gateway.findChargeForCheckout("abc").orElseThrow());
        }
    }

    @Test
    void refundUsesIdempotencyKey() throws StripeException {
        ArgumentCaptor<RequestOptions> optionsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
        Refund refund = mock(Refund.class);

        try (MockedStatic<Refund> mockedRefund = mockStatic(Refund.class)) {
            mockedRefund.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenReturn(refund);

            assertSame(refund, gateway.refund("ch_1", "attempt:x:refund-v1"));
            mockedRefund.verify(() -> Refund.create(any(RefundCreateParams.class), optionsCaptor.capture()));
        }

        assertEquals("attempt:x:refund-v1", optionsCaptor.getValue().getIdempotencyKey());
    }

    @Test
    void propagatesStripeFailures() {
        StripeException stripeException = mock(StripeException.class);

        try (MockedStatic<Charge> mockedCharge = mockStatic(Charge.class)) {
            mockedCharge.when(() -> Charge.create(any(ChargeCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(stripeException);

            assertThrows(StripeException.class,
                    () -> gateway.charge("tok_visa", 1000L, "try", "key", "abc"));
        }
    }
}
