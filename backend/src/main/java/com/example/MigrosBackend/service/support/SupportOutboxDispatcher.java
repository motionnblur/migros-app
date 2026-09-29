package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.entity.support.SupportOutboxEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumer half of the support outbox: claims due records, delivers them, and
 * records the outcome.
 *
 * <p>Every step is its own commit. The claim is a single conditional
 * {@code UPDATE}, the network call happens with no transaction and no row lock
 * held, and the completion is a fenced {@code UPDATE}. A worker that dies at
 * any point leaves the record claimable again once its bounded lease expires, so
 * a delivery is delayed at worst, never lost.
 *
 * <p>Delivery is at least once: a request that succeeded on the receiver but
 * whose response was lost will be retried. That is why the {@code eventId} in
 * the payload is stable across retries — the receiver deduplicates on it. The
 * alternative (at-most-once) would drop events instead, which for a support
 * conversation is the worse failure.
 */
@Service
public class SupportOutboxDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(SupportOutboxDispatcher.class);

    private final SupportOutboxStore outboxStore;
    private final RestTemplate restTemplate;
    private final String internalKey;
    private final Clock clock;
    private final long leaseSeconds;
    private final int maxAttempts;
    private final long backoffBaseSeconds;
    private final long backoffMaxSeconds;
    private final int pageSize;

    public SupportOutboxDispatcher(
            SupportOutboxStore outboxStore,
            RestTemplateBuilder restTemplateBuilder,
            Clock clock,
            @Value("${support.service.base-url:}") String supportServiceBaseUrl,
            @Value("${support.service.internal-key:}") String internalKey,
            @Value("${support.outbox.lease-seconds:120}") long leaseSeconds,
            @Value("${support.outbox.max-attempts:8}") int maxAttempts,
            @Value("${support.outbox.backoff-base-seconds:30}") long backoffBaseSeconds,
            @Value("${support.outbox.backoff-max-seconds:1800}") long backoffMaxSeconds,
            @Value("${support.outbox.page-size:20}") int pageSize
    ) {
        this.outboxStore = outboxStore;
        this.clock = clock;
        this.restTemplate = supportServiceBaseUrl == null || supportServiceBaseUrl.isBlank()
                ? null
                : restTemplateBuilder.rootUri(supportServiceBaseUrl).build();
        this.internalKey = internalKey;
        this.leaseSeconds = leaseSeconds;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffBaseSeconds = Math.max(1, backoffBaseSeconds);
        this.backoffMaxSeconds = Math.max(backoffBaseSeconds, backoffMaxSeconds);
        this.pageSize = Math.max(1, pageSize);
    }

    /**
     * Delivers one bounded page of due events. Returns the number of events this
     * call durably marked delivered.
     */
    public int deliverDueEvents() {
        if (restTemplate == null) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        List<String> dueEventIds = outboxStore.findDue(pageSize, now);

        int delivered = 0;
        for (String eventId : dueEventIds) {
            if (deliverOnce(eventId, now)) {
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * Claims, delivers, and records one event.
     *
     * @return {@code true} only when this worker durably marked it delivered
     */
    public boolean deliverOnce(String eventId, LocalDateTime now) {
        // A fresh token per attempt: it is the fencing token that proves to the
        // completion update that this worker still owns the record.
        String leaseOwner = UUID.randomUUID().toString();
        Optional<SupportOutboxStore.ClaimedEvent> claimed =
                outboxStore.tryClaim(eventId, leaseOwner, now, leaseSeconds);
        if (claimed.isEmpty()) {
            // Another worker holds a valid lease, or an earlier event for this
            // customer is still undelivered. Nothing to do; it stays queued.
            return false;
        }

        SupportOutboxStore.ClaimedEvent event = claimed.get();
        try {
            post(SupportOutboxEventType.valueOf(event.eventType()), event.payload());
        } catch (RuntimeException ex) {
            recordFailure(event, leaseOwner, now, ex);
            return false;
        }

        SupportOutboxStore.Transition transition =
                outboxStore.markDelivered(event.eventId(), leaseOwner, LocalDateTime.now(clock));
        if (transition != SupportOutboxStore.Transition.APPLIED) {
            // The lease was reclaimed while the request was in flight. The
            // receiver has the event, so a duplicate send is possible; the
            // stable eventId lets it discard the repeat. Never report success
            // for a record this worker no longer owns.
            LOG.warn("Support outbox event {} was delivered but its lease expired before completion", event.eventId());
        }
        return transition == SupportOutboxStore.Transition.APPLIED;
    }

    private void recordFailure(SupportOutboxStore.ClaimedEvent event, String leaseOwner,
                               LocalDateTime now, RuntimeException failure) {
        String errorCode = SupportOutboxStore.sanitizeError(failure);
        if (event.attemptCount() >= maxAttempts) {
            outboxStore.markExhausted(event.eventId(), leaseOwner, errorCode);
            LOG.error("Support outbox event {} exhausted {} attempts and needs operator attention",
                    event.eventId(), event.attemptCount());
            return;
        }

        LocalDateTime nextAttemptAt = now.plusSeconds(backoffSeconds(event.attemptCount()));
        outboxStore.scheduleRetry(event.eventId(), leaseOwner, errorCode, nextAttemptAt);
        // Event ids and error types only: the payload holds customer text.
        LOG.warn("Support outbox delivery failed for event {} ({}), retrying at {}",
                event.eventId(), errorCode, nextAttemptAt);
    }

    /** Exponential backoff, capped, with no overflow on long outages. */
    long backoffSeconds(int attemptCount) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 32));
        long delay = backoffBaseSeconds;
        for (int i = 0; i < exponent; i++) {
            if (delay >= backoffMaxSeconds) {
                return backoffMaxSeconds;
            }
            delay <<= 1;
        }
        return Math.min(delay, backoffMaxSeconds);
    }

    private void post(SupportOutboxEventType eventType, String payload) {
        try {
            restTemplate.postForEntity(eventType.path(), new HttpEntity<>(payload, buildHeaders()), Void.class);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to publish support internal event", ex);
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (internalKey != null && !internalKey.isBlank()) {
            headers.set("x-internal-key", internalKey);
        }
        return headers;
    }
}
