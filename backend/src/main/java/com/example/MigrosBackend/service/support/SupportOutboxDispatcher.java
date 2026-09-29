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
 * <p>The HTTP client has finite connect and read timeouts, and their
 * <em>sum</em> is kept well inside the delivery lease. Without them a single
 * hung connection would keep the lease far longer than intended, and the row
 * would be reclaimed and delivered again by another worker while the first
 * request was still in flight. The two budgets add up because a request spends
 * the connect timeout before it can even start the read timeout.
 *
 * <p>Every decision that needs a timestamp reads the clock at the moment it is
 * made, never once per batch. A batch is a sequence of independent deliveries,
 * each with its own send, and a scan that times its claims against the instant
 * it started hands every event a lease that is already partly spent by the
 * events before it: the lease lapses while the request is still in flight, and
 * the retry is scheduled from a timestamp the failure happened long after.
 */
@Service
public class SupportOutboxDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(SupportOutboxDispatcher.class);

    /**
     * Absolute floor for the headroom a lease keeps free for the completion
     * write, so a short lease is not configured down to zero slack.
     */
    private static final long MIN_LEASE_MARGIN_MILLIS = 1_000L;

    private final SupportOutboxStore outboxStore;
    private final RestTemplate restTemplate;
    private final String internalKey;
    private final Clock clock;
    private final long leaseSeconds;
    private final int escalationThreshold;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final long totalTimeoutMillis;
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
        this.totalTimeoutMillis = requireFitsInLease(
                this.connectTimeout.toMillis() + this.readTimeout.toMillis());
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

    /**
     * Both timeouts have to fit in one lease together, and with room to spare.
     *
     * <p>Checking each of them against the lease separately accepts a
     * configuration where neither is individually wrong and the pair is
     * unusable: a request can burn the whole connect budget and then the whole
     * read budget, because the read only starts once the connection is up. That
     * total is what the lease has to absorb, so it is the total that is
     * compared. A {@code 60s} connect plus a {@code 60s} read inside a
     * {@code 120s} lease passes both individual checks and still leaves a
     * request that outlives its lease.
     *
     * <p>The margin covers the work the lease does not measure: the completion
     * update after the request returns, and the ordinary scheduling delay of a
     * worker that is descheduled mid-send. Without it, a request that merely
     * fits would still be reclaimed while the worker is recording the outcome.
     */
    private long requireFitsInLease(long combinedMillis) {
        long leaseMillis = leaseSeconds * 1000L;
        long margin = leaseMarginMillis();
        if (combinedMillis + margin > leaseMillis) {
            throw new IllegalStateException("support.service.connect-timeout-ms plus "
                    + "support.service.read-timeout-ms (" + combinedMillis + "ms) must leave at least "
                    + margin + "ms of the support.outbox.lease-seconds budget (" + leaseMillis + "ms): "
                    + "a request can spend the connect timeout and then the read timeout, so the lease "
                    + "has to cover their sum, not each of them on its own");
        }
        return combinedMillis;
    }

    /**
     * Headroom kept free inside the lease: a tenth of it, and never less than a
     * second so that a very short lease still has room for the completion write.
     */
    private long leaseMarginMillis() {
        return Math.max(MIN_LEASE_MARGIN_MILLIS, leaseSeconds * 1000L / 10);
    }

    long connectTimeoutMillis() {
        return connectTimeout.toMillis();
    }

    long readTimeoutMillis() {
        return readTimeout.toMillis();
    }

    /** The worst-case in-flight time of one delivery attempt. */
    long totalTimeoutMillis() {
        return totalTimeoutMillis;
    }

    /**
     * Delivers one bounded page of due events. Returns the number of events this
     * call durably marked delivered.
     */
    public int deliverDueEvents() {
        if (restTemplate == null) {
            return 0;
        }

        // Only the scan is batched: it selects candidates once, and each claim
        // is timed on its own below.
        List<String> dueEventIds = outboxStore.findDue(pageSize, LocalDateTime.now(clock));

        int delivered = 0;
        for (String eventId : dueEventIds) {
            if (deliverOnce(eventId)) {
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
    public boolean deliverOnce(String eventId) {
        // Timed here, not once per batch: a slow event earlier in the page can
        // push this claim most of a lease into the past, and a lease that is
        // already spent when it is taken is a lease another worker may reclaim
        // while this request is still in flight.
        LocalDateTime claimedAt = LocalDateTime.now(clock);

        // A fresh token per attempt: it is the fencing token that proves to the
        // completion update that this worker still owns the record.
        String leaseOwner = UUID.randomUUID().toString();
        Optional<SupportOutboxStore.ClaimedEvent> claimed =
                outboxStore.tryClaim(eventId, leaseOwner, claimedAt, leaseSeconds);
        if (claimed.isEmpty()) {
            // Another worker holds a valid lease, or an earlier event for this
            // customer is still undelivered. Nothing to do; it stays queued.
            return false;
        }

        SupportOutboxStore.ClaimedEvent event = claimed.get();
        try {
            post(SupportOutboxEventType.valueOf(event.eventType()), event.payload());
        } catch (RuntimeException ex) {
            recordFailure(event, leaseOwner, ex);
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

    /**
     * Reschedules a failed attempt, measured from the moment it actually
     * failed.
     *
     * <p>Backoff has to start at the failure, not at the claim or at the start
     * of the batch. Measured from an earlier timestamp it is already partly
     * spent by the time it is written, so a send that took as long as its
     * backoff is retried immediately - which is a tight retry loop against a
     * receiver that is already refusing.
     */
    private void recordFailure(SupportOutboxStore.ClaimedEvent event, String leaseOwner,
                               RuntimeException failure) {
        LocalDateTime failedAt = LocalDateTime.now(clock);
        String errorCode = SupportOutboxStore.sanitizeError(failure);
        LocalDateTime nextAttemptAt = failedAt.plusSeconds(backoffSeconds(event.attemptCount()));
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
