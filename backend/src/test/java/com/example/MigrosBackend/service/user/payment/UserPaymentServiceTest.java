package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.service.user.supply.UserOrderService;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.param.ChargeCreateParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserPaymentServiceTest {
    @Mock
    private UserOrderService userOrderService;

    @InjectMocks
    private UserPaymentService userPaymentService;

    @Test
    void processCharge_Success() throws StripeException {
        String userToken = "user-123";
        String stripeToken = "tok_visa";
        Map<String, Object> payload = new HashMap<>();
        payload.put("token", stripeToken);

        when(userOrderService.getOrderPrice(userToken)).thenReturn(50.0f);

        try (MockedStatic<Charge> mockedCharge = mockStatic(Charge.class)) {
            Charge mockCharge = mock(Charge.class);
            when(mockCharge.getId()).thenReturn("ch_123");
            when(mockCharge.getAmount()).thenReturn(5000L);
            when(mockCharge.getStatus()).thenReturn("succeeded");

            mockedCharge.when(() -> Charge.create(any(ChargeCreateParams.class))).thenReturn(mockCharge);

            Map<String, Object> response = userPaymentService.processCharge(payload, userToken);

            assertNotNull(response);
            assertTrue((Boolean) response.get("success"));
            verify(userOrderService, times(1)).createOrder(userToken);
        }
    }

    @Test
    void processCharge_ReturnsNull_WhenAmountIsZeroOrLess() {
        String userToken = "user-123";
        Map<String, Object> payload = new HashMap<>();
        payload.put("token", "tok_visa");
        when(userOrderService.getOrderPrice(userToken)).thenReturn(0.0f);

        Map<String, Object> response = userPaymentService.processCharge(payload, userToken);

        assertNull(response);
        verify(userOrderService, never()).createOrder(any());
    }

    @Test
    void processCharge_ReturnsError_WhenStripeFails() throws StripeException {
        String userToken = "user-123";
        Map<String, Object> payload = new HashMap<>();
        payload.put("token", "tok_visa");
        when(userOrderService.getOrderPrice(userToken)).thenReturn(10.0f);

        try (MockedStatic<Charge> mockedCharge = mockStatic(Charge.class)) {
            mockedCharge.when(() -> Charge.create(any(ChargeCreateParams.class)))
                    .thenThrow(mock(StripeException.class));

            Map<String, Object> response = userPaymentService.processCharge(payload, userToken);

            assertFalse((Boolean) response.get("success"));
            assertTrue(response.get("error").toString().contains("Stripe error"));
            verify(userOrderService, never()).createOrder(any());
        }
    }

    @Test
    void processCharge_ReturnsError_WhenUnexpectedExceptionOccurs() {
        String userToken = "user-123";
        Map<String, Object> payload = new HashMap<>();
        payload.put("token", "tok_visa");
        when(userOrderService.getOrderPrice(userToken)).thenReturn(10.0f);

        try (MockedStatic<Charge> mockedCharge = mockStatic(Charge.class)) {
            mockedCharge.when(() -> Charge.create(any(ChargeCreateParams.class)))
                    .thenThrow(new RuntimeException("boom"));

            Map<String, Object> response = userPaymentService.processCharge(payload, userToken);

            assertFalse((Boolean) response.get("success"));
            assertTrue(response.get("error").toString().contains("Unexpected error"));
            verify(userOrderService, never()).createOrder(any());
        }
    }
}
