package com.example.MigrosBackend.service.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Drives support-outbox delivery and retention.
 *
 * <p>Delivery is scheduled rather than performed inline so a chat request never
 * waits on, or fails because of, the external support service. The scan is a
 * bounded page and claiming is atomic, so several instances can run it
 * concurrently without double-delivering.
 *
 * <p>Retention removes only {@code DELIVERED} records older than the configured
 * period. Undelivered records are never removed: they are still owed.
 */
@Component
public class SupportOutboxJob {

    private static final Logger LOG = LoggerFactory.getLogger(SupportOutboxJob.class);

    private final SupportOutboxDispatcher dispatcher;
    private final SupportOutboxStore outboxStore;
    private final Clock clock;
    private final int retentionDays;

    public SupportOutboxJob(SupportOutboxDispatcher dispatcher,
                            SupportOutboxStore outboxStore,
                            Clock clock,
                            @Value("${support.outbox.retention-days:30}") int retentionDays) {
        this.dispatcher = dispatcher;
        this.outboxStore = outboxStore;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    @Scheduled(
            fixedDelayString = "${support.outbox.scan-ms:5000}",
            initialDelayString = "${support.outbox.initial-delay-ms:5000}")
    public void deliverDueEvents() {
        try {
            dispatcher.deliverDueEvents();
        } catch (RuntimeException ex) {
            // A failing scan must not kill the scheduler: the records stay
            // claimable and the next tick retries them.
            LOG.error("Support outbox delivery scan failed: {}", ex.getClass().getSimpleName());
        }
    }

    @Scheduled(
            fixedDelayString = "${support.outbox.cleanup-scan-ms:3600000}",
            initialDelayString = "${support.outbox.cleanup-initial-delay-ms:3600000}")
    public void purgeDeliveredEvents() {
        int deleted = outboxStore.deleteDeliveredBefore(
                LocalDateTime.now(clock).minusDays(retentionDays));
        if (deleted > 0) {
            LOG.info("Support outbox retention deleted {} delivered events", deleted);
        }
    }
}
