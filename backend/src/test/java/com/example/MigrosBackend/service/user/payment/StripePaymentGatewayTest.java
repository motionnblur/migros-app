package com.example.MigrosBackend.service.user.payment;

import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.param.ChargeCreateParams;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class StripePaymentGatewayTest {
    private final StripePaymentGatewayImpl gateway = new StripePaymentGatewayImpl();

    @Test
    void sendsMinorUnitsAndCurrencyToStripe() throws StripeException {
        ArgumentCaptor<ChargeCreateParams> captor = ArgumentCaptor.forClass(ChargeCreateParams.class);
        Charge charge = mock(Charge.class);

        try (MockedStatic<Charge> mockedCharge = mockStatic(Charge.class)) {
            mockedCharge.when(() -> Charge.create(any(ChargeCreateParams.class))).thenReturn(charge);

            Charge result = gateway.charge("tok_visa", 5000L, "try");

            assertSame(charge, result);
            mockedCharge.verify(() -> Charge.create(captor.capture()));
        }

        assertEquals(5000L, captor.getValue().getAmount());
        assertEquals("try", captor.getValue().getCurrency());
        assertEquals("tok_visa", captor.getValue().getSource());
    }

    @Test
    void propagatesStripeFailures() {
        StripeException stripeException = mock(StripeException.class);

        try (MockedStatic<Charge> mockedCharge = mockStatic(Charge.class)) {
            mockedCharge.when(() -> Charge.create(any(ChargeCreateParams.class))).thenThrow(stripeException);

            assertThrows(StripeException.class, () -> gateway.charge("tok_visa", 1000L, "try"));
        }
    }
}
