package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.PaymentClaim;
import com.example.MigrosBackend.dto.payment.PaymentClaimDecision;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;
import com.example.MigrosBackend.service.user.payment.StripeEventStore.ReceiveOutcome;
import com.example.MigrosBackend.service.user.payment.StripeEventStore.StoredEvent;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

    private final Clock clock = Clock.systemDefaultZone();

    @BeforeEach
    void stubCompletion() {
        org.mockito.Mockito.lenient()
                .when(stripeEventStore.markProcessed(anyString(), any())).thenReturn(true);
    }

    private PaymentWebhookService service() {
        return new PaymentWebhookService(stripeEventStore, paymentAttemptService,
                paymentFinalizationService, clock, 120L, 8, 60L, 3600L);
    }

    private Event event(String eventId, String type) {
        Event event = mock(Event.class);
        when(event.getId()).thenReturn(eventId);
        when(event.getType()).thenReturn(type);
        return event;
    }

    private StoredEvent claimed(String eventId, String type) {
        return new StoredEvent(eventId, type, null, "hash", "PROCESSING", 1);
    }

    @Test
    void repeatedEventIdIsProcessedOnlyOnce() {
        PaymentWebhookService webhookService = service();
        Event event = event("evt_duplicate", "unhandled.event");
        when(stripeEventStore.receive(eq("evt_duplicate"), eq("unhandled.event"),
                any(), anyString(), any()))
                .thenReturn(ReceiveOutcome.RECEIVED_NEW)
                .thenReturn(ReceiveOutcome.ALREADY_PROCESSED);
        when(stripeEventStore.tryClaim(eq("evt_duplicate"), anyString(), any(), anyLong()))
                .thenReturn(Optional.of(claimed("evt_duplicate", "unhandled.event")));

        webhookService.handle(event, "{}");
        webhookService.handle(event, "{}");

        verify(stripeEventStore, times(2)).receive(eq("evt_duplicate"), eq("unhandled.event"),
                any(), anyString(), any());
        verify(stripeEventStore, times(1)).tryClaim(eq("evt_duplicate"), anyString(), any(), anyLong());
        verify(stripeEventStore, times(1)).markProcessed(eq("evt_duplicate"), any());
        verify(paymentAttemptService, never()).recordChargeSuccess(any(), any(), anyString());
    }

    @Test
    void conflictingContentIsHeldForManualReviewWithoutEffects() {
        PaymentWebhookService webhookService = service();
        Event event = event("evt_collision", "charge.succeeded");
        when(stripeEventStore.receive(eq("evt_collision"), eq("charge.succeeded"),
                any(), anyString(), any()))
                .thenReturn(ReceiveOutcome.CONFLICT);

        webhookService.handle(event, "{\"tampered\":true}");

        verify(stripeEventStore).markManualReview("evt_collision", "event_id_collision");
        verify(stripeEventStore, never()).tryClaim(anyString(), anyString(), any(), anyLong());
        verify(paymentAttemptService, never()).recordChargeSuccess(any(), any(), anyString());
        verify(paymentFinalizationService, never()).finalizeOrder(any(), any(), anyString());
    }

    @Test
    void activeLeaseLeavesEventAlone() {
        PaymentWebhookService webhookService = service();
        Event event = event("evt_claimed", "charge.succeeded");
        when(stripeEventStore.receive(eq("evt_claimed"), eq("charge.succeeded"),
                any(), anyString(), any()))
                .thenReturn(ReceiveOutcome.NEEDS_PROCESSING);
        when(stripeEventStore.tryClaim(eq("evt_claimed"), anyString(), any(), anyLong()))
                .thenReturn(Optional.empty());

        webhookService.handle(event, "{}");

        verify(stripeEventStore, never()).markProcessed(anyString(), any());
        verify(paymentAttemptService, never()).recordChargeSuccess(any(), any(), anyString());
        verify(paymentFinalizationService, never()).finalizeOrder(any(), any(), anyString());
    }

    @Test
    void chargeSucceededRecordsProviderChargeThenFinalizes() {
        PaymentWebhookService webhookService = service();
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
        when(stripeEventStore.receive(eq("evt_1"), eq("charge.succeeded"),
                any(), anyString(), any()))
                .thenReturn(ReceiveOutcome.RECEIVED_NEW);
        when(stripeEventStore.tryClaim(eq("evt_1"), anyString(), any(), anyLong()))
                .thenReturn(Optional.of(claimed("evt_1", "charge.succeeded")));

        PaymentClaim claim = new PaymentClaim(PaymentClaimDecision.PROCEED, attemptId, checkoutId,
                "checkout:" + checkoutId + ":charge-v1", 1000L, "try", null, null,
                PaymentAttemptStatus.PROCESSING);
        when(paymentAttemptService.findByProviderCharge("ch_1")).thenReturn(null);
        when(paymentAttemptService.findByCheckoutId(checkoutId)).thenReturn(claim);

        webhookService.handle(event, "{}");

        verify(paymentAttemptService).recordChargeSuccess(attemptId, null, "ch_1");
        verify(paymentFinalizationService).finalizeOrder(attemptId, checkoutId, "ch_1");
        verify(stripeEventStore).markProcessed(eq("evt_1"), any());
    }

    @Test
    void economicsMismatchMovesAttemptToManualReview() {
        PaymentWebhookService webhookService = service();
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
        when(stripeEventStore.receive(eq("evt_mismatch"), eq("charge.succeeded"),
                any(), anyString(), any()))
                .thenReturn(ReceiveOutcome.RECEIVED_NEW);
        when(stripeEventStore.tryClaim(eq("evt_mismatch"), anyString(), any(), anyLong()))
                .thenReturn(Optional.of(claimed("evt_mismatch", "charge.succeeded")));

        PaymentClaim claim = new PaymentClaim(PaymentClaimDecision.PROCEED, attemptId, checkoutId,
                "checkout:" + checkoutId + ":charge-v1", 1000L, "try", null, null,
                PaymentAttemptStatus.PROCESSING);
        when(paymentAttemptService.findByProviderCharge("ch_mismatch")).thenReturn(null);
        when(paymentAttemptService.findByCheckoutId(checkoutId)).thenReturn(claim);

        webhookService.handle(event, "{}");

        verify(paymentAttemptService).markManualReview(attemptId, "provider_amount_mismatch");
        verify(paymentAttemptService, never()).recordChargeSuccess(any(), any(), anyString());
    }
}
