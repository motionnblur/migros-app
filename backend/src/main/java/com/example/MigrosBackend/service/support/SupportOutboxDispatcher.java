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
import java.time.Duration;
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
 *
 * <p>Delivery also never gives up. A failed attempt is rescheduled with capped
 * exponential backoff no matter how many have already happened, because an event
 * that stops being retried is both lost and - since {@code findDue} only holds
 * back a queue behind rows that are still {@code PENDING} or {@code PROCESSING}
 * - a hole that lets every later event for that customer jump the queue. The
 * configured attempt count is therefore a logging threshold for operator
 * attention, not a delivery budget.
 *
 * <p>The HTTP client has finite connect and read timeouts, themselves shorter
 * than the delivery lease. Without them a single hung connection would keep the
 * lease far longer than intended, and the row would be reclaimed and delivered
 * again by another worker while the first request was still in flight.
 */
@Service
public class SupportOutboxDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(SupportOutboxDispatcher.class);

    private final SupportOutboxStore outboxStore;
    private final RestTemplate restTemplate;
    private final String internalKey;
    private final Clock clock;
    private final long leaseSeconds;
    private final int escalationThreshold;
    private final Duration connectTimeout;
    private final Duration readTimeout;
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
            @Value("${support.outbox.alert-after-attempts:8}") int escalationThreshold,
            @Value("${support.service.connect-timeout-ms:2000}") long connectTimeoutMillis,
            @Value("${support.service.read-timeout-ms:10000}") long readTimeoutMillis,
            @Value("${support.outbox.backoff-base-seconds:30}") long backoffBaseSeconds,
            @Value("${support.outbox.backoff-max-seconds:1800}") long backoffMaxSeconds,
            @Value("${support.outbox.page-size:20}") int pageSize
    ) {
        this.outboxStore = outboxStore;
        this.clock = clock;
        this.leaseSeconds = requirePositive(leaseSeconds, "support.outbox.lease-seconds");
        this.connectTimeout = boundedTimeout(connectTimeoutMillis, "support.service.connect-timeout-ms");
        this.readTimeout = boundedTimeout(readTimeoutMillis, "support.service.read-timeout-ms");
        this.escalationThreshold = Math.max(1, escalationThreshold);
        this.restTemplate = supportServiceBaseUrl == null || supportServiceBaseUrl.isBlank()
                ? null
                : restTemplateBuilder
                .rootUri(supportServiceBaseUrl)
                .connectTimeout(this.connectTimeout)
                .readTimeout(this.readTimeout)
                .build();
        this.internalKey = internalKey;
        this.backoffBaseSeconds = Math.max(1, backoffBaseSeconds);
        this.backoffMaxSeconds = Math.max(backoffBaseSeconds, backoffMaxSeconds);
        this.pageSize = Math.max(1, pageSize);
    }

    private long requirePositive(long value, String property) {
        if (value <= 0) {
            throw new IllegalStateException(property + " must be a positive number of seconds");
        }
        return value;
    }

    /**
     * A delivery attempt has to finish, or fail, inside the lease it holds.
     *
     * <p>A timeout at or above the lease is not merely slow: the lease expires
     * while the request is still in flight, another worker reclaims the row, and
     * the same event is delivered concurrently by both. Rejecting the
     * misconfiguration at startup is the only way to keep that from silently
     * becoming the delivery guarantee.
     */
    private Duration boundedTimeout(long millis, String property) {
        if (millis <= 0) {
            throw new IllegalStateException(property + " must be a positive number of milliseconds");
        }
        Duration timeout = Duration.ofMillis(millis);
        if (timeout.compareTo(Duration.ofSeconds(leaseSeconds)) >= 0) {
            throw new IllegalStateException(property + " must be shorter than support.outbox.lease-seconds ("
                    + leaseSeconds + "s), otherwise a delivery lease can expire while a request is still in flight");
        }
        return timeout;
    }

    long connectTimeoutMillis() {
        return connectTimeout.toMillis();
    }

    long readTimeoutMillis() {
        return readTimeout.toMillis();
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
        LocalDateTime nextAttemptAt = now.plusSeconds(backoffSeconds(event.attemptCount()));
        outboxStore.scheduleRetry(event.eventId(), leaseOwner, errorCode, nextAttemptAt);

        // Event ids and error types only: the payload holds customer text.
        if (event.attemptCount() >= escalationThreshold) {
            // Still retrying, but a receiver that has refused this many times is
            // an incident an operator has to see rather than a blip to retry
            // quietly. This is a logging threshold, never a give-up point.
            LOG.error("Support outbox event {} has failed {} times in a row ({}); still retrying at {}, "
                            + "the receiver is refusing delivery",
                    event.eventId(), event.attemptCount(), errorCode, nextAttemptAt);
            return;
        }
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
