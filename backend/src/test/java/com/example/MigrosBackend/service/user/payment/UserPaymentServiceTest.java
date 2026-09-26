package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.CheckoutPaymentStart;
import com.example.MigrosBackend.dto.payment.CheckoutStatusDto;
import com.example.MigrosBackend.dto.payment.PaymentResponseDto;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserPaymentServiceTest {

    private static final String USER_TOKEN = "user-token";
    private static final String STRIPE_TOKEN = "tok_visa";

    @Mock
    private CheckoutService checkoutService;
    @Mock
    private StripePaymentGateway stripePaymentGateway;

    private UserPaymentService userPaymentService;
    private UUID checkoutId;

    @BeforeEach
    void setUp() {
        userPaymentService = new UserPaymentService(checkoutService, stripePaymentGateway);
        checkoutId = UUID.randomUUID();
    }

    private CheckoutStatusDto status(String state) {
        return new CheckoutStatusDto(checkoutId.toString(), state, new BigDecimal("50.99"), 5099L, "try",
                LocalDateTime.now(), LocalDateTime.now().plusMinutes(10), 700L, "ch_123");
    }

    @Test
    void processCharge_ChargesSnapshotAmountAndForwardsCheckoutId() throws StripeException {
        when(checkoutService.beginPayment(USER_TOKEN, checkoutId))
                .thenReturn(new CheckoutPaymentStart(checkoutId, 5099L, "try"));

        Charge charge = org.mockito.Mockito.mock(Charge.class);
        when(charge.getId()).thenReturn("ch_123");
        when(stripePaymentGateway.charge(STRIPE_TOKEN, 5099L, "try")).thenReturn(charge);
        when(checkoutService.completePayment(checkoutId, "ch_123")).thenReturn(status("CONSUMED"));

        PaymentResponseDto response = userPaymentService.processCharge(checkoutId, STRIPE_TOKEN, USER_TOKEN);

        assertTrue(response.success());
        assertFalse(response.pending());
        assertEquals("CONSUMED", response.status());
        assertEquals("ch_123", response.chargeId());
        assertEquals(5099L, response.amountMinor());

        ArgumentCaptor<Long> amount = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> currency = ArgumentCaptor.forClass(String.class);
        verify(stripePaymentGateway).charge(eq(STRIPE_TOKEN), amount.capture(), currency.capture());
        assertEquals(5099L, amount.getValue());
        assertEquals("try", currency.getValue());
    }

    @Test
    void processCharge_StripeFailure_ReleasesReservationAndReportsFailure() throws StripeException {
        when(checkoutService.beginPayment(USER_TOKEN, checkoutId))
                .thenReturn(new CheckoutPaymentStart(checkoutId, 1000L, "try"));
        when(stripePaymentGateway.charge(anyString(), eq(1000L), eq("try")))
                .thenThrow(org.mockito.Mockito.mock(StripeException.class));

        PaymentResponseDto response = userPaymentService.processCharge(checkoutId, STRIPE_TOKEN, USER_TOKEN);

        assertFalse(response.success());
        assertEquals("CANCELLED", response.status());
        assertTrue(response.error().contains("Stripe error"));
        verify(checkoutService).failPayment(checkoutId);
        verify(checkoutService, never()).completePayment(any(), anyString());
    }

    @Test
    void processCharge_FinalizationFailure_KeepsChargeAndReservationRecoverable() throws StripeException {
        when(checkoutService.beginPayment(USER_TOKEN, checkoutId))
                .thenReturn(new CheckoutPaymentStart(checkoutId, 1000L, "try"));
        Charge charge = org.mockito.Mockito.mock(Charge.class);
        when(charge.getId()).thenReturn("ch_123");
        when(stripePaymentGateway.charge(STRIPE_TOKEN, 1000L, "try")).thenReturn(charge);
        when(checkoutService.completePayment(checkoutId, "ch_123"))
                .thenThrow(new GeneralException("order transaction failed"));

        PaymentResponseDto response = userPaymentService.processCharge(checkoutId, STRIPE_TOKEN, USER_TOKEN);

        assertTrue(response.success());
        assertTrue(response.pending());
        assertEquals("ch_123", response.chargeId());
        verify(checkoutService, never()).failPayment(any());
    }

    @Test
    void processCharge_RejectsBlankTokenWithoutCallingStripe() throws StripeException {
        assertThrows(GeneralException.class,
                () -> userPaymentService.processCharge(checkoutId, "   ", USER_TOKEN));
        verify(stripePaymentGateway, never()).charge(anyString(), anyLong(), anyString());
        verify(checkoutService, never()).beginPayment(any(), any());
    }

    @Test
    void processCharge_DoesNotIncludeCardTokenInResponse() throws StripeException {
        when(checkoutService.beginPayment(USER_TOKEN, checkoutId))
                .thenReturn(new CheckoutPaymentStart(checkoutId, 1000L, "try"));
        Charge charge = org.mockito.Mockito.mock(Charge.class);
        when(charge.getId()).thenReturn("ch_123");
        when(stripePaymentGateway.charge(STRIPE_TOKEN, 1000L, "try")).thenReturn(charge);
        when(checkoutService.completePayment(checkoutId, "ch_123")).thenReturn(status("CONSUMED"));

        PaymentResponseDto response = userPaymentService.processCharge(checkoutId, STRIPE_TOKEN, USER_TOKEN);

        assertNull(response.error());
    }
}
