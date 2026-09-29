package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.entity.support.SupportOutboxEventType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

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
 */
class SupportOutboxDispatcherTimeoutTest {

    private static final long LEASE_SECONDS = 120;

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
