package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.entity.support.SupportOutboxEventType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The delivery lease and the HTTP timeouts that bound a delivery attempt have to
 * agree with each other.
 *
 * <p>A delivery holds a lease on its outbox row for as long as its request is in
 * flight. If a request can outlive that lease, the row is reclaimed and
 * delivered again by another worker while the first request is still running:
 * the same event is then in flight twice, which is a correctness question, not a
 * performance one. A timeout at or above the lease is therefore rejected at
 * startup rather than accepted and discovered in production.
 *
 * <p>What has to fit in the lease is the <em>sum</em> of the two timeouts. They
 * are spent one after the other, so a pair that is individually legal can still
 * add up to more than the lease it has to survive.
 */
class SupportOutboxDispatcherTimeoutTest {

    private static final long LEASE_SECONDS = 120;

    /** Headroom the lease keeps free: a tenth of it, never less than a second. */
    private static long leaseMarginMillis() {
        return Math.max(1_000L, LEASE_SECONDS * 1000L / 10);
    }

    @Test
    void theConfiguredTimeoutsAreFiniteAndShorterThanTheLease() {
        SupportOutboxDispatcher dispatcher = dispatcher(LEASE_SECONDS, 2000, 10000);

        assertTrue(dispatcher.connectTimeoutMillis() > 0, "connect timeout must be finite");
        assertTrue(dispatcher.readTimeoutMillis() > 0, "read timeout must be finite");
        assertTrue(dispatcher.connectTimeoutMillis() < LEASE_SECONDS * 1000,
                "a connect timeout at or above the lease lets another worker deliver the same event");
        assertTrue(dispatcher.readTimeoutMillis() < LEASE_SECONDS * 1000,
                "a read timeout at or above the lease lets another worker deliver the same event");
    }

    /**
     * The two budgets are spent one after the other, so the lease has to cover
     * the sum. The sum is also kept clear of the lease by a margin, because the
     * completion write after the request returns has to fit in it too.
     */
    @Test
    void theWorstCaseRequestTimeFitsInsideTheLeaseWithMarginToSpare() {
        SupportOutboxDispatcher dispatcher = dispatcher(LEASE_SECONDS, 2000, 10000);

        assertEquals(12000, dispatcher.totalTimeoutMillis());
        assertTrue(dispatcher.totalTimeoutMillis() + leaseMarginMillis() <= LEASE_SECONDS * 1000,
                "a delivery that can spend both timeouts must still leave room to record its outcome "
                        + "inside the lease it holds");
    }

    /**
     * A configuration can pass both individual checks and still be unusable.
     *
     * <p>Each timeout is comfortably shorter than the lease, but a request can
     * spend the connect budget and then the read budget, so a 60s + 60s pair
     * inside a 120s lease takes twice as long as the lease it has to survive -
     * the row is reclaimed and delivered again while the first request is still
     * in flight.
     */
    @Test
    void timeoutsThatAreEachShorterThanTheLeaseButTogetherOutliveItAreRejected() {
        long each = 60_000L;

        assertTrue(each < LEASE_SECONDS * 1000, "this test is about timeouts that are individually legal");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> dispatcher(LEASE_SECONDS, each, each));

        assertTrue(failure.getMessage().contains("connect-timeout"), failure.getMessage());
        assertTrue(failure.getMessage().contains("read-timeout"), failure.getMessage());
        assertTrue(failure.getMessage().contains("lease-seconds"), failure.getMessage());
        assertTrue(failure.getMessage().contains(String.valueOf(2 * each)), failure.getMessage());
    }

    @Test
    void aRequestThatLeavesOnlyTheMarginIsRejectedBecauseCompletionHasToFitToo() {
        // 110s of timeouts inside a 120s lease leaves exactly the 12s margin and
        // therefore no room at all for the completion update.
        assertThrows(IllegalStateException.class, () -> dispatcher(LEASE_SECONDS, 60_000, 50_000));
    }

    @Test
    void aRequestThatLeavesRoomForCompletionIsAccepted() {
        assertDoesNotThrow(() -> dispatcher(LEASE_SECONDS, 50_000, 50_000));
    }

    @Test
    void aReadTimeoutThatOutlivesTheLeaseIsRejected() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> dispatcher(LEASE_SECONDS, 2000, LEASE_SECONDS * 1000));

        assertTrue(failure.getMessage().contains("read-timeout"), failure.getMessage());
        assertTrue(failure.getMessage().contains("lease"), failure.getMessage());
    }

    @Test
    void aConnectTimeoutThatOutlivesTheLeaseIsRejected() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> dispatcher(LEASE_SECONDS, LEASE_SECONDS * 1000 + 1, 10000));

        assertTrue(failure.getMessage().contains("connect-timeout"), failure.getMessage());
    }

    @Test
    void anUnboundedTimeoutIsRejected() {
        assertThrows(IllegalStateException.class, () -> dispatcher(LEASE_SECONDS, 0, 10000));
        assertThrows(IllegalStateException.class, () -> dispatcher(LEASE_SECONDS, 2000, -1));
    }

    @Test
    void aNonPositiveLeaseIsRejected() {
        assertThrows(IllegalStateException.class, () -> dispatcher(0, 2000, 10000));
    }

    @Test
    void theOutboxRejectsAnEventWithoutAStableIdOrCustomer() {
        SupportOutboxStore store = new SupportOutboxStore(mock(JdbcTemplate.class));

        assertThrows(IllegalArgumentException.class, () -> store.enqueue(
                "", SupportOutboxEventType.CUSTOMER_MESSAGE_CREATED.name(), "a@b.c", "{}", null));
        assertThrows(IllegalArgumentException.class, () -> store.enqueue(
                "id", SupportOutboxEventType.CUSTOMER_MESSAGE_CREATED.name(), " ", "{}", null));
    }

    private SupportOutboxDispatcher dispatcher(long leaseSeconds, long connectMillis, long readMillis) {
        return new SupportOutboxDispatcher(
                mock(SupportOutboxStore.class),
                new RestTemplateBuilder(),
                Clock.systemUTC(),
                "http://127.0.0.1:1",
                "internal-key",
                leaseSeconds,
                8,
                connectMillis,
                readMillis,
                30,
                1800,
                20);
    }

    @Test
    void backoffSaturatesInsteadOfOverflowing() {
        SupportOutboxDispatcher dispatcher = dispatcher(LEASE_SECONDS, 2000, 10000);

        assertEquals(30, dispatcher.backoffSeconds(1));
        assertEquals(60, dispatcher.backoffSeconds(2));
        assertEquals(1800, dispatcher.backoffSeconds(100));
        assertEquals(1800, dispatcher.backoffSeconds(Integer.MAX_VALUE));
    }
}
