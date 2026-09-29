package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.entity.support.SupportOutboxEventType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A batch of due events is a sequence of independent deliveries, and every one of
 * them takes real time.
 *
 * <p>Timing all of them against the instant the scan started hands each event two
 * timestamps from a moment that has already passed. The lease it claims with is
 * partly spent by the events before it, so the row can be reclaimed and
 * delivered concurrently by another worker while this request is still in
 * flight; and the retry is scheduled from a timestamp the failure happened long
 * after, so a send that lasted as long as its own backoff is retried
 * immediately - a tight loop against a receiver that is already refusing.
 *
 * <p>The clock here advances a fixed step on every read, which is what elapsed
 * time looks like to code that is not waiting for it, and makes the interleaving
 * exact instead of timing-dependent. A real loopback receiver stands in for the
 * support service, so the failure path exercised is the one production takes.
 */
class SupportOutboxDispatcherClockTest {

    private static final long LEASE_SECONDS = 120;
    private static final long BACKOFF_BASE_SECONDS = 30;

    /**
     * Longer than the backoff, so a backoff measured from the wrong timestamp is
     * visibly spent before it is even written down, and a large part of the
     * lease, so a lease measured from the wrong timestamp is visibly already
     * lapsing.
     */
    private static final long SEND_STEP_SECONDS = 45;

    private static final String FIRST_EVENT = "event-first";
    private static final String SECOND_EVENT = "event-second";
    private static final LocalDateTime SCAN_TIME = LocalDateTime.of(2026, 1, 1, 12, 0, 0);

    private SteppingClock clock;
    private SupportOutboxStore store;
    private HttpServer receiver;
    private AtomicInteger responseStatus;

    @BeforeEach
    void setUp() throws IOException {
        clock = new SteppingClock(SCAN_TIME, SEND_STEP_SECONDS);
        store = mock(SupportOutboxStore.class);
        responseStatus = new AtomicInteger(503);
        receiver = respondingReceiver(responseStatus);
        when(store.findDue(anyInt(), any())).thenReturn(List.of(FIRST_EVENT, SECOND_EVENT));
        when(store.tryClaim(anyString(), anyString(), any(), anyLong()))
                .thenAnswer(call -> Optional.of(claimFor(call.getArgument(0, String.class))));
    }

    @AfterEach
    void tearDown() {
        receiver.stop(0);
    }

    @Test
    void aSlowEarlierEventDoesNotSpendTheLeaseOrTheBackoffOfTheNextOne() {
        assertEquals(0, dispatcher().deliverDueEvents(),
                "both deliveries are refused, so nothing is delivered and both are rescheduled");

        List<LocalDateTime> claimTimes = claimTimesInOrder();
        List<LocalDateTime> retryTimes = retryTimesInOrder();
        assertEquals(2, claimTimes.size());
        assertEquals(2, retryTimes.size());

        assertTrue(claimTimes.get(0).isAfter(SCAN_TIME),
                "a claim must be timed when it is taken, not when the batch began");
        assertTrue(claimTimes.get(1).isAfter(claimTimes.get(0)),
                "an earlier slow send must not hand the next event the batch's timestamp");
        assertTrue(claimTimes.get(1).isAfter(retryTimes.get(0)),
                "the second event is claimed after the first attempt has already failed");

        for (int i = 0; i < claimTimes.size(); i++) {
            LocalDateTime failedAt = claimTimes.get(i).plusSeconds(SEND_STEP_SECONDS);

            assertTrue(claimTimes.get(i).plusSeconds(LEASE_SECONDS).isAfter(failedAt),
                    "the lease has to cover the whole send that follows the claim, otherwise the row is "
                            + "reclaimed and delivered again while the request is still in flight");

            assertEquals(failedAt.plusSeconds(BACKOFF_BASE_SECONDS), retryTimes.get(i),
                    "the retry must be scheduled from the moment the send actually failed");
            assertTrue(retryTimes.get(i).isAfter(claimTimes.get(i)),
                    "a retry scheduled at or before its own claim is due immediately, which is a tight "
                            + "loop against a receiver that is already refusing");
        }
    }

    @Test
    void aDeliveredEventIsCompletedAgainstTheMomentItsRequestReturned() {
        responseStatus.set(202);
        when(store.findDue(anyInt(), any())).thenReturn(List.of(FIRST_EVENT));
        when(store.markDelivered(anyString(), anyString(), any()))
                .thenReturn(SupportOutboxStore.Transition.APPLIED);

        assertEquals(1, dispatcher().deliverDueEvents());

        ArgumentCaptor<LocalDateTime> completedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(store).markDelivered(eq(FIRST_EVENT), anyString(), completedAt.capture());
        assertTrue(completedAt.getValue().isAfter(SCAN_TIME),
                "completion is recorded when the request returned, not when the batch started");
    }

    /** A lease bought from a stale timestamp cannot cover the send that follows. */
    @Test
    void everyClaimGetsAFullLeaseMeasuredFromItsOwnMoment() {
        dispatcher().deliverDueEvents();

        List<LocalDateTime> claimTimes = claimTimesInOrder();
        ArgumentCaptor<Long> leaseSeconds = ArgumentCaptor.forClass(Long.class);
        verify(store, times(2)).tryClaim(anyString(), anyString(), any(), leaseSeconds.capture());
        assertEquals(List.of(LEASE_SECONDS, LEASE_SECONDS), leaseSeconds.getAllValues());

        for (int i = 0; i + 1 < claimTimes.size(); i++) {
            assertTrue(claimTimes.get(i).isBefore(claimTimes.get(i + 1)),
                    "each claim is timed at its own moment, not at the start of the batch");
            assertTrue(claimTimes.get(i).plusSeconds(LEASE_SECONDS).isAfter(claimTimes.get(i + 1)),
                    "the lease of one attempt must still be live while the next attempt is claiming");
        }
    }

    private SupportOutboxDispatcher dispatcher() {
        return new SupportOutboxDispatcher(
                store,
                new RestTemplateBuilder(),
                clock,
                "http://127.0.0.1:" + receiver.getAddress().getPort(),
                "internal-key",
                LEASE_SECONDS,
                8,
                2_000,
                10_000,
                BACKOFF_BASE_SECONDS,
                1_800,
                20);
    }

    private SupportOutboxStore.ClaimedEvent claimFor(String eventId) {
        return new SupportOutboxStore.ClaimedEvent(
                eventId,
                SupportOutboxEventType.CUSTOMER_MESSAGE_CREATED.name(),
                "customer@migros.com",
                "{\"eventId\":\"" + eventId + "\"}",
                1,
                "lease-owner");
    }

    private List<LocalDateTime> claimTimesInOrder() {
        ArgumentCaptor<LocalDateTime> claimedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(store, times(2)).tryClaim(anyString(), anyString(), claimedAt.capture(), anyLong());
        return claimedAt.getAllValues();
    }

    private List<LocalDateTime> retryTimesInOrder() {
        ArgumentCaptor<LocalDateTime> nextAttemptAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(store, times(2))
                .scheduleRetry(anyString(), anyString(), anyString(), nextAttemptAt.capture());
        return nextAttemptAt.getAllValues();
    }

    /** A receiver that answers with the given status, read at request time. */
    private static HttpServer respondingReceiver(AtomicInteger status) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    /**
     * A clock that jumps a fixed step on every read. Each read stands for the time
     * a delivery spends in flight, so the second event is claimed and the first is
     * failed well after the scan timestamp.
     */
    private static final class SteppingClock extends Clock {

        private final Instant start;
        private final long stepSeconds;
        private int reads;

        private SteppingClock(LocalDateTime start, long stepSeconds) {
            this.start = start.toInstant(ZoneOffset.UTC);
            this.stepSeconds = stepSeconds;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return start.plusSeconds(stepSeconds * reads++);
        }
    }
}
