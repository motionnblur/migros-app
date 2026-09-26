package com.example.MigrosBackend.service.user.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Bounded recovery and retention for the durable Stripe webhook inbox.
 *
 * <p>Recovery replays, in a bounded page, every event that is not durably
 * complete: unclaimed receipts, claims whose processing lease expired (a
 * worker crashed mid-processing), and retries whose backoff elapsed. Claiming
 * stays atomic inside {@link PaymentWebhookService#processStoredEvent}, so
 * this job racing a live redelivery cannot double-process an event; webhook
 * effects are idempotent regardless.
 *
 * <p>Retention deletes only {@code PROCESSED} events older than the
 * configured retention period. Unresolved events are never removed. Stored
 * payloads are sensitive operational data: only event ids and counts appear
 * in logs, never payloads, signatures, or secrets.
 */
@Component
public class StripeWebhookRecoveryJob {

    private static final Logger LOG = LoggerFactory.getLogger(StripeWebhookRecoveryJob.class);

    private final PaymentWebhookService paymentWebhookService;
    private final StripeEventStore stripeEventStore;
    private final Clock clock;
    private final int pageSize;
    private final int retentionDays;

    public StripeWebhookRecoveryJob(PaymentWebhookService paymentWebhookService,
                                    StripeEventStore stripeEventStore,
                                    Clock clock,
                                    @Value("${payment.webhook.inbox-page-size:20}") int pageSize,
                                    @Value("${payment.webhook.inbox-retention-days:30}") int retentionDays) {
        this.paymentWebhookService = paymentWebhookService;
        this.stripeEventStore = stripeEventStore;
        this.clock = clock;
        this.pageSize = pageSize;
        this.retentionDays = retentionDays;
    }

    @Scheduled(
            fixedDelayString = "${payment.webhook.inbox-scan-ms:60000}",
            initialDelayString = "${payment.webhook.inbox-initial-delay-ms:60000}")
    public void recoverDueWebhooks() {
        List<String> dueIds = stripeEventStore.findDue(pageSize, LocalDateTime.now(clock));
        for (String eventId : dueIds) {
            try {
                paymentWebhookService.processStoredEvent(eventId);
            } catch (RuntimeException ex) {
                LOG.error("Webhook inbox recovery failed for event {}: {}",
                        eventId, ex.getClass().getSimpleName());
            }
        }
    }

    @Scheduled(
            fixedDelayString = "${payment.webhook.inbox-cleanup-scan-ms:3600000}",
            initialDelayString = "${payment.webhook.inbox-cleanup-initial-delay-ms:3600000}")
    public void purgeProcessedWebhooks() {
        int deleted = stripeEventStore.deleteProcessedBefore(
                LocalDateTime.now(clock).minusDays(retentionDays));
        if (deleted > 0) {
            LOG.info("Webhook inbox retention deleted {} processed events", deleted);
        }
    }
}
