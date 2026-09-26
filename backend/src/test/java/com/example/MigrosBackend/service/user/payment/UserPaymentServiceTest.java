package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.PaymentClaim;
import com.example.MigrosBackend.dto.payment.PaymentClaimDecision;
import com.example.MigrosBackend.dto.payment.PaymentResponseDto;
import com.example.MigrosBackend.dto.payment.PaymentStatusDto;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserPaymentServiceTest {

    private static final String USER_TOKEN = "user-token";
    private static final String STRIPE_TOKEN = "tok_visa";

    @Mock
    private StripePaymentGateway stripePaymentGateway;
    @Mock
    private PaymentAttemptService paymentAttemptService;
    @Mock
    private PaymentFinalizationService paymentFinalizationService;

    private UserPaymentService userPaymentService;
    private UUID checkoutId;
    private UUID attemptId;
    private String idempotencyKey;

    @BeforeEach
    void setUp() {
        userPaymentService = new UserPaymentService(
                stripePaymentGateway, paymentAttemptService, paymentFinalizationService);
        checkoutId = UUID.randomUUID();
        attemptId = UUID.randomUUID();
        idempotencyKey = "checkout:" + checkoutId + ":charge-v1";
    }

    private PaymentClaim claim(PaymentClaimDecision decision, PaymentAttemptStatus state) {
        return new PaymentClaim(decision, attemptId, checkoutId, idempotencyKey, 5099L, "try",
                "lease-1", state.hasDurableCharge() ? "ch_123" : null, state);
    }

    private PaymentStatusDto status(String state, boolean finalized, boolean pending) {
        return new PaymentStatusDto(checkoutId.toString(), attemptId.toString(), "CONSUMED", state,
                "ch_123", new BigDecimal("50.99"), 5099L, "try", 700L, finalized, pending, false);
    }

    @Test
    void processCharge_UsesStoredAmountCurrencyAndStableIdempotencyKey() throws StripeException {
        when(paymentAttemptService.claim(USER_TOKEN, checkoutId, idempotencyKey))
                .thenReturn(claim(PaymentClaimDecision.PROCEED, PaymentAttemptStatus.PROCESSING));
        Charge charge = mock(Charge.class);
        when(charge.getId()).thenReturn("ch_123");
        when(stripePaymentGateway.charge(eq(STRIPE_TOKEN), eq(5099L), eq("try"), eq(idempotencyKey),
                eq(checkoutId.toString()))).thenReturn(charge);
        when(paymentFinalizationService.finalizeOrder(attemptId, checkoutId, "ch_123")).thenReturn(true);
        when(paymentAttemptService.getStatus(USER_TOKEN, checkoutId))
                .thenReturn(status("ORDER_FINALIZED", true, false));

        PaymentResponseDto response = userPaymentService.processCharge(checkoutId, STRIPE_TOKEN, USER_TOKEN);

        assertTrue(response.success());
        assertFalse(response.pending());
        assertEquals("ORDER_FINALIZED", response.state());
        assertEquals(5099L, response.amountMinor());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(stripePaymentGateway).charge(eq(STRIPE_TOKEN), eq(5099L), eq("try"), key.capture(),
                eq(checkoutId.toString()));
        assertEquals(idempotencyKey, key.getValue());
        verify(paymentAttemptService).recordChargeSuccess(attemptId, "lease-1", "ch_123");
    }

    @Test
    void processCharge_ProviderDeclineRecordsTerminalFailureAndNeverFinalizes() throws StripeException {
        when(paymentAttemptService.claim(USER_TOKEN, checkoutId, idempotencyKey))
                .thenReturn(claim(PaymentClaimDecision.PROCEED, PaymentAttemptStatus.PROCESSING));
        CardException decline = mock(CardException.class);
        when(decline.getCode()).thenReturn("card_declined");
        when(stripePaymentGateway.charge(eq(STRIPE_TOKEN), eq(5099L), eq("try"), eq(idempotencyKey),
                eq(checkoutId.toString()))).thenThrow(decline);
        when(paymentAttemptService.getStatus(USER_TOKEN, checkoutId))
                .thenReturn(status("FAILED_FINAL", false, false));

        PaymentResponseDto response = userPaymentService.processCharge(checkoutId, STRIPE_TOKEN, USER_TOKEN);

        assertFalse(response.success());
        assertFalse(response.pending());
        assertEquals("FAILED_FINAL", response.state());
        verify(paymentAttemptService).recordDecline(attemptId, "card_declined");
        verify(paymentAttemptService, never()).recordChargeSuccess(any(), any(), any());
        verify(paymentFinalizationService, never()).finalizeOrder(any(), any(), any());
    }

    @Test
    void processCharge_AmbiguousStripeErrorStaysRecoverableAndNeverDeclines() throws StripeException {
        when(paymentAttemptService.claim(USER_TOKEN, checkoutId, idempotencyKey))
                .thenReturn(claim(PaymentClaimDecision.PROCEED, PaymentAttemptStatus.PROCESSING));
        when(stripePaymentGateway.charge(eq(STRIPE_TOKEN), eq(5099L), eq("try"), eq(idempotencyKey),
                eq(checkoutId.toString()))).thenThrow(new ApiConnectionException("timeout"));
        when(paymentAttemptService.getStatus(USER_TOKEN, checkoutId))
                .thenReturn(status("PROCESSING", false, true));

        PaymentResponseDto response = userPaymentService.processCharge(checkoutId, STRIPE_TOKEN, USER_TOKEN);

        assertFalse(response.success());
        assertTrue(response.pending());
        assertEquals("PROCESSING", response.state());
        verify(paymentAttemptService, never()).recordDecline(any(), any());
    }

    @Test
    void processCharge_FinalizationFailureIsRecoverableNotADecline() throws StripeException {
        when(paymentAttemptService.claim(USER_TOKEN, checkoutId, idempotencyKey))
                .thenReturn(claim(PaymentClaimDecision.PROCEED, PaymentAttemptStatus.PROCESSING));
        Charge charge = mock(Charge.class);
        when(charge.getId()).thenReturn("ch_123");
        when(stripePaymentGateway.charge(anyString(), eq(5099L), eq("try"), eq(idempotencyKey),
                eq(checkoutId.toString()))).thenReturn(charge);
        when(paymentFinalizationService.finalizeOrder(attemptId, checkoutId, "ch_123")).thenReturn(false);
        when(paymentAttemptService.getStatus(USER_TOKEN, checkoutId))
                .thenReturn(status("CHARGE_SUCCEEDED", false, true));

        PaymentResponseDto response = userPaymentService.processCharge(checkoutId, STRIPE_TOKEN, USER_TOKEN);

        assertTrue(response.success());
        assertTrue(response.pending());
        assertEquals("CHARGE_SUCCEEDED", response.state());
        verify(paymentAttemptService, never()).recordDecline(any(), any());
    }

    @Test
    void processCharge_RepeatedFinalizedCheckoutReturnsStoredResultWithoutCharging() throws StripeException {
        when(paymentAttemptService.claim(USER_TOKEN, checkoutId, idempotencyKey))
                .thenReturn(claim(PaymentClaimDecision.FINALIZED, PaymentAttemptStatus.ORDER_FINALIZED));
        when(paymentAttemptService.getStatus(USER_TOKEN, checkoutId))
                .thenReturn(status("ORDER_FINALIZED", true, false));

        PaymentResponseDto response = userPaymentService.processCharge(checkoutId, STRIPE_TOKEN, USER_TOKEN);

        assertTrue(response.success());
        assertFalse(response.pending());
        verify(stripePaymentGateway, never()).charge(any(), any(Long.class), any(), any(), any());
    }

    @Test
    void processCharge_ActiveLeaseReturnsPendingWithoutChargingAgain() throws StripeException {
        when(paymentAttemptService.claim(USER_TOKEN, checkoutId, idempotencyKey))
                .thenReturn(claim(PaymentClaimDecision.PENDING, PaymentAttemptStatus.PROCESSING));
        when(paymentAttemptService.getStatus(USER_TOKEN, checkoutId))
                .thenReturn(status("PROCESSING", false, true));

        PaymentResponseDto response = userPaymentService.processCharge(checkoutId, STRIPE_TOKEN, USER_TOKEN);

        assertFalse(response.success());
        assertTrue(response.pending());
        verify(stripePaymentGateway, never()).charge(any(), any(Long.class), any(), any(), any());
    }

    @Test
    void getPaymentStatus_RetriesOrderFinalizationForStoredCharge() {
        PaymentStatusDto pending = status("CHARGE_SUCCEEDED", false, true);
        PaymentStatusDto finalized = status("ORDER_FINALIZED", true, false);
        when(paymentAttemptService.getStatus(USER_TOKEN, checkoutId))
                .thenReturn(pending)
                .thenReturn(finalized);
        when(paymentFinalizationService.finalizeOrder(attemptId, checkoutId, "ch_123")).thenReturn(true);

        PaymentStatusDto result = userPaymentService.getPaymentStatus(USER_TOKEN, checkoutId);

        assertEquals("ORDER_FINALIZED", result.state());
        verify(paymentFinalizationService).finalizeOrder(attemptId, checkoutId, "ch_123");
    }

    @Test
    void processCharge_RejectsBlankTokenWithoutClaiming() throws StripeException {
        assertThrows(GeneralException.class,
                () -> userPaymentService.processCharge(checkoutId, "   ", USER_TOKEN));
        verify(paymentAttemptService, never()).claim(any(), any(), anyString());
        verify(stripePaymentGateway, never()).charge(any(), any(Long.class), any(), any(), any());
    }

    @Test
    void processCharge_ResponseNeverContainsProviderExceptionText() throws StripeException {
        when(paymentAttemptService.claim(USER_TOKEN, checkoutId, idempotencyKey))
                .thenReturn(claim(PaymentClaimDecision.PROCEED, PaymentAttemptStatus.PROCESSING));
        CardException decline = mock(CardException.class);
        when(decline.getCode()).thenReturn("card_declined");
        when(stripePaymentGateway.charge(anyString(), any(Long.class), anyString(), anyString(), anyString()))
                .thenThrow(decline);
        when(paymentAttemptService.getStatus(USER_TOKEN, checkoutId))
                .thenReturn(status("FAILED_FINAL", false, false));

        PaymentResponseDto response = userPaymentService.processCharge(checkoutId, STRIPE_TOKEN, USER_TOKEN);

        assertFalse(response.error() == null || response.error().isBlank());
        assertFalse(response.error().toLowerCase().contains("exception"));
    }
}
