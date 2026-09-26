package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.exception.user.PaymentAmountException;
import com.example.MigrosBackend.service.user.supply.UserOrderService;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserPaymentServiceTest {
    @Mock
    private UserOrderService userOrderService;

    @Mock
    private StripePaymentGateway stripePaymentGateway;

    private final PaymentAmountConverter paymentAmountConverter = new PaymentAmountConverter("try");

    private UserPaymentService userPaymentService;

    @BeforeEach
    void setUp() {
        userPaymentService = new UserPaymentService(userOrderService, paymentAmountConverter, stripePaymentGateway);
    }

    private Map<String, Object> payloadWithToken(String token) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("token", token);
        return payload;
    }

    @Test
    void processCharge_Success() throws StripeException {
        String userToken = "user-123";
        String stripeToken = "tok_visa";

        when(userOrderService.getOrderPrice(userToken)).thenReturn(new BigDecimal("50.00"));

        Charge mockCharge = mock(Charge.class);
        when(mockCharge.getId()).thenReturn("ch_123");
        when(mockCharge.getAmount()).thenReturn(5000L);
        when(mockCharge.getCurrency()).thenReturn("try");
        when(mockCharge.getStatus()).thenReturn("succeeded");
        when(stripePaymentGateway.charge(stripeToken, 5000L, "try")).thenReturn(mockCharge);

        Map<String, Object> response = userPaymentService.processCharge(payloadWithToken(stripeToken), userToken);

        assertNotNull(response);
        assertTrue((Boolean) response.get("success"));
        verify(stripePaymentGateway).charge(stripeToken, 5000L, "try");
        verify(userOrderService, times(1)).createOrder(userToken);
    }

    @Test
    void processCharge_SubmitsMinorUnits_ForFractionalTotal() throws StripeException {
        String userToken = "user-123";
        String stripeToken = "tok_visa";

        when(userOrderService.getOrderPrice(userToken)).thenReturn(new BigDecimal("50.99"));

        Charge mockCharge = mock(Charge.class);
        when(stripePaymentGateway.charge(eq(stripeToken), anyLong(), anyString())).thenReturn(mockCharge);

        userPaymentService.processCharge(payloadWithToken(stripeToken), userToken);

        ArgumentCaptor<Long> amountCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> currencyCaptor = ArgumentCaptor.forClass(String.class);
        verify(stripePaymentGateway).charge(eq(stripeToken), amountCaptor.capture(), currencyCaptor.capture());

        assertEquals(5099L, amountCaptor.getValue());
        assertEquals("try", currencyCaptor.getValue());
    }

    @Test
    void processCharge_ThrowsPaymentAmountException_WhenAmountIsZero() {
        String userToken = "user-123";
        when(userOrderService.getOrderPrice(userToken)).thenReturn(BigDecimal.ZERO);

        assertThrows(PaymentAmountException.class,
                () -> userPaymentService.processCharge(payloadWithToken("tok_visa"), userToken));

        verifyNoInteractions(stripePaymentGateway);
        verify(userOrderService, never()).createOrder(any());
    }

    @Test
    void processCharge_ThrowsPaymentAmountException_WhenAmountIsNegative() {
        String userToken = "user-123";
        when(userOrderService.getOrderPrice(userToken)).thenReturn(new BigDecimal("-5.00"));

        assertThrows(PaymentAmountException.class,
                () -> userPaymentService.processCharge(payloadWithToken("tok_visa"), userToken));

        verifyNoInteractions(stripePaymentGateway);
        verify(userOrderService, never()).createOrder(any());
    }

    @Test
    void processCharge_ThrowsPaymentAmountException_WhenAmountOverflows() {
        String userToken = "user-123";
        when(userOrderService.getOrderPrice(userToken)).thenReturn(new BigDecimal("1E20"));

        assertThrows(PaymentAmountException.class,
                () -> userPaymentService.processCharge(payloadWithToken("tok_visa"), userToken));

        verifyNoInteractions(stripePaymentGateway);
        verify(userOrderService, never()).createOrder(any());
    }

    @Test
    void processCharge_ThrowsPaymentAmountException_WhenAmountIsOverPrecise() {
        String userToken = "user-123";
        when(userOrderService.getOrderPrice(userToken)).thenReturn(new BigDecimal("10.001"));

        assertThrows(PaymentAmountException.class,
                () -> userPaymentService.processCharge(payloadWithToken("tok_visa"), userToken));

        verifyNoInteractions(stripePaymentGateway);
        verify(userOrderService, never()).createOrder(any());
    }

    @Test
    void processCharge_ThrowsPaymentAmountException_WhenAmountExceedsStripeTryLimit() {
        String userToken = "user-123";
        when(userOrderService.getOrderPrice(userToken)).thenReturn(new BigDecimal("1000000.00"));

        assertThrows(PaymentAmountException.class,
                () -> userPaymentService.processCharge(payloadWithToken("tok_visa"), userToken));

        verifyNoInteractions(stripePaymentGateway);
        verify(userOrderService, never()).createOrder(any());
    }

    @Test
    void processCharge_ThrowsPaymentAmountException_WhenAmountIsNull() {
        String userToken = "user-123";
        when(userOrderService.getOrderPrice(userToken)).thenReturn(null);

        assertThrows(PaymentAmountException.class,
                () -> userPaymentService.processCharge(payloadWithToken("tok_visa"), userToken));

        verifyNoInteractions(stripePaymentGateway);
        verify(userOrderService, never()).createOrder(any());
    }

    @Test
    void processCharge_ReturnsError_WhenStripeFails() throws StripeException {
        String userToken = "user-123";
        when(userOrderService.getOrderPrice(userToken)).thenReturn(new BigDecimal("10.00"));
        when(stripePaymentGateway.charge(anyString(), anyLong(), anyString()))
                .thenThrow(mock(StripeException.class));

        Map<String, Object> response = userPaymentService.processCharge(payloadWithToken("tok_visa"), userToken);

        assertFalse((Boolean) response.get("success"));
        assertTrue(response.get("error").toString().contains("Stripe error"));
        verify(userOrderService, never()).createOrder(any());
    }

    @Test
    void processCharge_ReturnsError_WhenUnexpectedExceptionOccurs() throws StripeException {
        String userToken = "user-123";
        when(userOrderService.getOrderPrice(userToken)).thenReturn(new BigDecimal("10.00"));
        when(stripePaymentGateway.charge(anyString(), anyLong(), anyString()))
                .thenThrow(new RuntimeException("boom"));

        Map<String, Object> response = userPaymentService.processCharge(payloadWithToken("tok_visa"), userToken);

        assertFalse((Boolean) response.get("success"));
        assertTrue(response.get("error").toString().contains("Unexpected error"));
        verify(userOrderService, never()).createOrder(any());
    }
}
