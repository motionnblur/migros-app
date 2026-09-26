package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.PaymentClaim;
import com.example.MigrosBackend.dto.payment.PaymentClaimDecision;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookServiceTest {

    @Mock
    private StripeEventStore stripeEventStore;
    @Mock
    private PaymentAttemptService paymentAttemptService;
    @Mock
    private PaymentFinalizationService paymentFinalizationService;

    private PaymentWebhookService webhookService;

    private PaymentWebhookService service() {
        return new PaymentWebhookService(stripeEventStore, paymentAttemptService,
                paymentFinalizationService);
    }

    private Event event(String eventId, String type) {
        Event event = mock(Event.class);
        when(event.getId()).thenReturn(eventId);
        when(event.getType()).thenReturn(type);
        return event;
    }

    @Test
    void repeatedEventIdIsProcessedOnlyOnce() {
        webhookService = service();
        Event event = event("evt_duplicate", "unhandled.event");
        when(stripeEventStore.markIfNew(eq("evt_duplicate"), eq("unhandled.event"), any()))
                .thenReturn(true)
                .thenReturn(false);

        webhookService.handle(event);
        webhookService.handle(event);

        verify(stripeEventStore, times(2)).markIfNew(eq("evt_duplicate"), eq("unhandled.event"), any());
        verify(stripeEventStore, times(1)).markProcessed("evt_duplicate");
        verify(paymentAttemptService, never()).recordChargeSuccess(any(), any(), anyString());
    }

    @Test
    void chargeSucceededRecordsProviderChargeThenFinalizes() {
        webhookService = service();
        UUID checkoutId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        Charge charge = new Charge();
        charge.setId("ch_1");
        charge.setPaid(true);
        charge.setAmount(1000L);
        charge.setCurrency("try");
        Map<String, String> metadata = new HashMap<>();
        metadata.put("checkout_id", checkoutId.toString());
        charge.setMetadata(metadata);

        Event event = event("evt_1", "charge.succeeded");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(charge));
        when(stripeEventStore.markIfNew(eq("evt_1"), eq("charge.succeeded"), any())).thenReturn(true);

        PaymentClaim claim = new PaymentClaim(PaymentClaimDecision.PROCEED, attemptId, checkoutId,
                "checkout:" + checkoutId + ":charge-v1", 1000L, "try", null, null,
                PaymentAttemptStatus.PROCESSING);
        when(paymentAttemptService.findByProviderCharge("ch_1")).thenReturn(null);
        when(paymentAttemptService.findByCheckoutId(checkoutId)).thenReturn(claim);

        webhookService.handle(event);

        verify(paymentAttemptService).recordChargeSuccess(attemptId, null, "ch_1");
        verify(paymentFinalizationService).finalizeOrder(attemptId, checkoutId, "ch_1");
        verify(stripeEventStore).markProcessed("evt_1");
    }

    @Test
    void economicsMismatchMovesAttemptToManualReview() {
        webhookService = service();
        UUID checkoutId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        Charge charge = new Charge();
        charge.setId("ch_mismatch");
        charge.setAmount(42L);
        charge.setCurrency("try");
        Map<String, String> metadata = new HashMap<>();
        metadata.put("checkout_id", checkoutId.toString());
        charge.setMetadata(metadata);

        Event event = event("evt_mismatch", "charge.succeeded");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(charge));
        when(stripeEventStore.markIfNew(eq("evt_mismatch"), eq("charge.succeeded"), any())).thenReturn(true);

        PaymentClaim claim = new PaymentClaim(PaymentClaimDecision.PROCEED, attemptId, checkoutId,
                "checkout:" + checkoutId + ":charge-v1", 1000L, "try", null, null,
                PaymentAttemptStatus.PROCESSING);
        when(paymentAttemptService.findByProviderCharge("ch_mismatch")).thenReturn(null);
        when(paymentAttemptService.findByCheckoutId(checkoutId)).thenReturn(claim);

        webhookService.handle(event);

        verify(paymentAttemptService).markManualReview(attemptId, "provider_amount_mismatch");
        verify(paymentAttemptService, never()).recordChargeSuccess(any(), any(), anyString());
    }
}
