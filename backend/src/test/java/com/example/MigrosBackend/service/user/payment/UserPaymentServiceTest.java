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
        // Arrange
        String userToken = "user-123";
        String stripeToken = "tok_visa";
        Map<String, Object> payload = new HashMap<>();
        payload.put("token", stripeToken);
        payload.put("userToken", userToken);

        when(userOrderService.getOrderPrice(userToken)).thenReturn(50.0f);

        // Mocking the static Stripe Charge.create method
        try (MockedStatic<Charge> mockedCharge = mockStatic(Charge.class)) {
            Charge mockCharge = mock(Charge.class);
            when(mockCharge.getId()).thenReturn("ch_123");
            when(mockCharge.getAmount()).thenReturn(5000L); // Stripe uses cents usually, but following your cast
            when(mockCharge.getStatus()).thenReturn("succeeded");

            mockedCharge.when(() -> Charge.create(any(ChargeCreateParams.class))).thenReturn(mockCharge);

            // Act
            Map<String, Object> response = userPaymentService.processCharge(payload);

            // Assert
            assertNotNull(response);
            assertTrue((Boolean) response.get("success"));
            verify(userOrderService, times(1)).createOrder(userToken);
        }
    }

    @Test
    void processCharge_ReturnsNull_WhenAmountIsZeroOrLess() {
        // Arrange
        Map<String, Object> payload = new HashMap<>();
        payload.put("userToken", "user-123");
        when(userOrderService.getOrderPrice("user-123")).thenReturn(0.0f);

        // Act
        Map<String, Object> response = userPaymentService.processCharge(payload);

        // Assert
        assertNull(response);
        verify(userOrderService, never()).createOrder(anyString());
    }

    @Test
    void processCharge_ReturnsError_WhenStripeFails() throws StripeException {
        // Arrange
        Map<String, Object> payload = new HashMap<>();
        payload.put("userToken", "user-123");
        when(userOrderService.getOrderPrice("user-123")).thenReturn(10.0f);

        try (MockedStatic<Charge> mockedCharge = mockStatic(Charge.class)) {
            mockedCharge.when(() -> Charge.create(any(ChargeCreateParams.class)))
                    .thenThrow(mock(StripeException.class));

            // Act
            Map<String, Object> response = userPaymentService.processCharge(payload);

            // Assert
            assertFalse((Boolean) response.get("success"));
            assertTrue(response.get("error").toString().contains("Stripe error"));
            verify(userOrderService, never()).createOrder(anyString());
        }
    }
}