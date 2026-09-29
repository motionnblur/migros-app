package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.entity.support.SupportOutboxEventType;
import com.example.MigrosBackend.entity.support.SupportOutboxStatus;
import com.example.MigrosBackend.entity.user.SupportMessageEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.repository.user.SupportMessageEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The support outbox must survive a commit boundary, retry with a stable
 * {@code eventId}, reclaim work abandoned by a dead worker, and never reorder one
 * customer's events.
 *
 * <p>A real loopback HTTP server stands in for the external support service, so
 * the transport, the internal-key header, and the actual failure path are
 * exercised rather than mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class SupportOutboxPostgresTest {

    private static final String USER_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String ADMIN_SECRET = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";
    private static final String INTERNAL_KEY = "integration-test-internal-key";
    private static final String MAIL = "chat@migros.com";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    /** Stands in for the external support service and records what it receives. */
    static final class RecordingSupportService implements AutoCloseable {

        record Received(String path, String body, String internalKey) {
        }

        private final HttpServer server;
        private final List<Received> received = new CopyOnWriteArrayList<>();
        private volatile int failuresRemaining;

        RecordingSupportService() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String body;
                try (InputStream in = exchange.getRequestBody()) {
                    body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                received.add(new Received(exchange.getRequestURI().getPath(), body,
                        exchange.getRequestHeaders().getFirst("x-internal-key")));

                int remaining = failuresRemaining;
                if (remaining > 0) {
                    failuresRemaining = remaining - 1;
                    exchange.sendResponseHeaders(503, -1);
                } else {
                    exchange.sendResponseHeaders(202, -1);
                }
                exchange.close();
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void failNextDeliveries(int count) {
            failuresRemaining = count;
        }

        void reset() {
            received.clear();
            failuresRemaining = 0;
        }

        List<Received> received() {
            return new ArrayList<>(received);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static RecordingSupportService supportService;

    @BeforeAll
    static void startSupportService() throws IOException {
        supportService = new RecordingSupportService();
    }

    @AfterAll
    static void stopSupportService() {
        supportService.close();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jwt.user-secret", () -> USER_SECRET);
        registry.add("jwt.admin-secret", () -> ADMIN_SECRET);
        registry.add("support.internal.key", () -> INTERNAL_KEY);
        registry.add("support.service.internal-key", () -> INTERNAL_KEY);
        registry.add("support.service.base-url", () -> supportService.baseUrl());
        // Long delays: these tests drive delivery explicitly and must not race
        // the scheduled scan.
        registry.add("support.outbox.scan-ms", () -> "3600000");
        registry.add("support.outbox.initial-delay-ms", () -> "3600000");
        registry.add("support.outbox.cleanup-scan-ms", () -> "3600000");
        registry.add("support.outbox.cleanup-initial-delay-ms", () -> "3600000");
        registry.add("support.outbox.lease-seconds", () -> "60");
        registry.add("support.outbox.max-attempts", () -> "4");
        registry.add("support.outbox.backoff-base-seconds", () -> "60");
        registry.add("support.outbox.backoff-max-seconds", () -> "3600");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserEntityRepository userEntityRepository;
    @Autowired
    private SupportMessageEntityRepository supportMessageEntityRepository;
    @Autowired
    private SupportChatService supportChatService;
    @Autowired
    private SupportOutboxDispatcher dispatcher;
    @Autowired
    private SupportOutboxStore outboxStore;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private ObjectMapper objectMapper;

    @Value("${support.outbox.lease-seconds}")
    private long leaseSeconds;
    @Value("${support.outbox.max-attempts}")
    private int maxAttempts;
    @Value("${support.outbox.backoff-base-seconds}")
    private long backoffBaseSeconds;
    @Value("${support.outbox.backoff-max-seconds}")
    private long backoffMaxSeconds;

    @BeforeEach
    void resetState() {
        supportService.reset();
        jdbcTemplate.execute("TRUNCATE TABLE support_outbox_entity, support_messages, user_entity "
                + "RESTART IDENTITY CASCADE");
    }

    @Test
    void aMessageIsDeliveredOnlyAfterTheTransactionCommits() {
        sendUserMessage("hello");

        // The message is committed and the event is owed, but nothing has been
        // delivered yet: publishing inline is what let a slow or dead support
        // service block, or silently break, the chat request.
        assertNotNull(userEntityRepository.findByUserMail(MAIL));
        assertEquals(1, supportMessageEntityRepository.findByUserMailOrderByCreatedAtAscIdAsc(MAIL).size());
        assertEquals(1, pendingCount());
        assertTrue(supportService.received().isEmpty());

        assertEquals(1, dispatcher.deliverDueEvents());

        assertEquals(0, pendingCount());
        assertEquals(1, deliveredCount());
        assertEquals(1, supportService.received().size());
        RecordingSupportService.Received received = supportService.received().get(0);
        assertEquals(SupportOutboxEventType.CUSTOMER_MESSAGE_CREATED.path(), received.path());
        assertEquals(INTERNAL_KEY, received.internalKey());
        assertEquals(MAIL, eventField(received.body(), "customerId"));
        assertEquals("hello", eventField(received.body(), "text"));
    }

    @Test
    void aFailedDeliveryIsRetriedAndTheEventIdIsStableAcrossRetries() {
        sendUserMessage("retry me");
        supportService.failNextDeliveries(1);

        assertEquals(0, dispatcher.deliverDueEvents());
        assertEquals(1, supportService.received().size());
        assertEquals(SupportOutboxStatus.PENDING, statusOfOnlyRow(),
                "a failed attempt must stay owed, not be lost");
        assertEquals(1, attemptCountOfOnlyRow());
        assertNotNull(lastErrorOfOnlyRow());

        // The backoff has not elapsed yet, so nothing is claimable.
        assertTrue(outboxStore.findDue(10, LocalDateTime.now()).isEmpty());

        // Move the retry into the past the way waiting out the backoff would.
        makeNextAttemptDue();

        assertEquals(1, dispatcher.deliverDueEvents());
        assertEquals(2, supportService.received().size());
        assertEquals(1, deliveredCount());
        assertEquals(2, attemptCountOfOnlyRow());

        assertEquals(eventIdOf(supportService.received().get(0).body()),
                eventIdOf(supportService.received().get(1).body()),
                "at-least-once delivery only works if the receiver can deduplicate, "
                        + "so the eventId must be identical on every attempt");
    }

    @Test
    void deliveryIsRetriedWithExponentialBackoffUpToTheConfiguredCap() {
        assertEquals(backoffBaseSeconds, dispatcher.backoffSeconds(1));
        assertEquals(backoffBaseSeconds * 2, dispatcher.backoffSeconds(2));
        assertEquals(backoffBaseSeconds * 4, dispatcher.backoffSeconds(3));
        assertEquals(backoffMaxSeconds, dispatcher.backoffSeconds(20));
        assertEquals(backoffMaxSeconds, dispatcher.backoffSeconds(1_000_000),
                "an extreme attempt count must saturate instead of overflowing");
    }

    @Test
    void anEventThatExhaustsItsAttemptsIsParkedForAnOperator() {
        sendUserMessage("give up");

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            supportService.failNextDeliveries(1);
            dispatcher.deliverDueEvents();
            makeNextAttemptDue();
        }

        assertEquals(SupportOutboxStatus.FAILED, statusOfOnlyRow(),
                "an event nobody can deliver must stop retrying and stay visible");
        assertEquals(maxAttempts, attemptCountOfOnlyRow());
        assertTrue(outboxStore.findDue(10, LocalDateTime.now()).isEmpty());

        // A terminal event must also stop blocking its customer's queue.
        sendUserMessage("next");
        assertEquals(1, dispatcher.deliverDueEvents(),
                "an exhausted event must not hold up the customer's later events forever");
    }

    @Test
    void anEventAbandonedByADeadWorkerIsReclaimedAfterTheLeaseExpires() {
        sendUserMessage("worker died");

        String eventId = eventIdOfOnlyRow();
        assertTrue(outboxStore.tryClaim(eventId, "dead-worker", LocalDateTime.now(), leaseSeconds).isPresent());
        assertEquals(SupportOutboxStatus.PROCESSING, statusOfOnlyRow());
        assertTrue(outboxStore.findDue(10, LocalDateTime.now()).isEmpty(),
                "a live lease must keep other workers away");

        // The worker never came back, so its bounded lease lapses.
        expireLease(eventId);

        assertEquals(List.of(eventId), outboxStore.findDue(10, LocalDateTime.now()));
        assertEquals(1, dispatcher.deliverDueEvents());
        assertEquals(1, deliveredCount());
    }

    @Test
    void aWorkerThatLostItsLeaseCannotMarkTheEventDelivered() {
        sendUserMessage("fencing");
        String eventId = eventIdOfOnlyRow();

        assertTrue(outboxStore.tryClaim(eventId, "first-worker", LocalDateTime.now(), leaseSeconds).isPresent());

        // Another worker reclaims the row, replacing the lease token.
        expireLease(eventId);
        assertTrue(outboxStore.tryClaim(eventId, "second-worker", LocalDateTime.now(), leaseSeconds).isPresent());

        assertEquals(SupportOutboxStore.Transition.STALE_CLAIM,
                outboxStore.markDelivered(eventId, "first-worker", LocalDateTime.now()));
        assertEquals(SupportOutboxStore.Transition.STALE_CLAIM,
                outboxStore.scheduleRetry(eventId, "first-worker", "boom", LocalDateTime.now()));
        assertEquals(SupportOutboxStore.Transition.STALE_CLAIM,
                outboxStore.markExhausted(eventId, "first-worker", "boom"));
        assertEquals(SupportOutboxStore.Transition.STALE_CLAIM,
                outboxStore.markDelivered(eventId, null, LocalDateTime.now()));

        assertEquals(SupportOutboxStatus.PROCESSING, statusOfOnlyRow(),
                "a stale worker must not be able to close or reschedule someone else's claim");
    }

    @Test
    void oneCustomersEventsAreDeliveredInCommitOrder() {
        sendUserMessage("first");
        sendUserMessage("second");
        sendUserMessage("third");

        // Only the head of the customer's queue is claimable at a time.
        assertEquals(1, outboxStore.findDue(50, LocalDateTime.now()).size(),
                "per-customer ordering means one event in flight per conversation");

        List<String> deliveredTexts = new ArrayList<>();
        while (!outboxStore.findDue(50, LocalDateTime.now()).isEmpty()) {
            dispatcher.deliverDueEvents();
            for (RecordingSupportService.Received received : supportService.received()) {
                String text = eventField(received.body(), "text");
                if (!deliveredTexts.contains(text)) {
                    deliveredTexts.add(text);
                }
            }
        }

        assertEquals(List.of("first", "second", "third"), deliveredTexts);
    }

    @Test
    void aFailedEventDoesNotLetLaterEventsOfTheSameCustomerOvertakeIt() {
        sendUserMessage("first");
        sendUserMessage("second");

        supportService.failNextDeliveries(1);
        dispatcher.deliverDueEvents();
        makeNextAttemptDue();

        // "second" is due, but "first" is still owed, so only "first" is claimable.
        List<String> due = outboxStore.findDue(50, LocalDateTime.now());
        assertEquals(1, due.size());
        assertEquals("first", eventField(storedPayloadOf(due.get(0)), "text"));
    }

    @Test
    void differentCustomersDoNotBlockEachOther() {
        sendUserMessageFor(MAIL, "a-one");
        sendUserMessageFor("other@migros.com", "b-one");
        sendUserMessageFor(MAIL, "a-two");
        sendUserMessageFor("other@migros.com", "b-two");

        assertEquals(2, outboxStore.findDue(50, LocalDateTime.now()).size(),
                "one head-of-line event per customer may be in flight at once");
    }

    @Test
    void concurrentWorkersNeverDoubleDeliverTheSameEvent() throws Exception {
        for (int i = 0; i < 8; i++) {
            sendUserMessageFor("customer" + i + "@migros.com", "message-" + i);
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> scans = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                scans.add(dispatcher::deliverDueEvents);
            }
            int claimed = 0;
            for (Future<Integer> future : pool.invokeAll(scans, 60, TimeUnit.SECONDS)) {
                claimed += future.get();
            }
            assertEquals(8, claimed);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(8, deliveredCount());
        assertEquals(8, supportService.received().size(),
                "at-least-once allows duplicates only after an ambiguous failure, never on a clean run");
        assertEquals(8, supportService.received().stream()
                .map(received -> eventIdOf(received.body()))
                .distinct().count());
    }

    @Test
    void deliveredEventsAreRemovedByRetentionButUndeliveredOnesAreNot() {
        sendUserMessage("retained");
        assertEquals(1, dispatcher.deliverDueEvents());

        jdbcTemplate.update("UPDATE support_outbox_entity SET delivered_at = ? WHERE delivered_at IS NOT NULL",
                Timestamp.valueOf(LocalDateTime.now().minusDays(40)));
        sendUserMessageFor("other@migros.com", "still owed");

        assertEquals(1, outboxStore.deleteDeliveredBefore(LocalDateTime.now().minusDays(30)));
        assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM support_outbox_entity", Integer.class),
                "an event still owed must never be deleted by retention cleanup");
        assertEquals(SupportOutboxStatus.PENDING, statusOfOnlyRow());
    }

    @Test
    void anEditAndADeleteAreRecordedAsTheirOwnOutboxEvents() {
        sendUserMessage("original");
        long messageId = supportMessageEntityRepository
                .findByUserMailOrderByCreatedAtAscIdAsc(MAIL).get(0).getId();
        assertEquals(1, dispatcher.deliverDueEvents());

        supportChatService.editMessageForAdmin(MAIL, messageId, "edited");
        supportChatService.deleteMessageForAdmin(MAIL, messageId);

        assertEquals(2, pendingCount());
        assertEquals(1, dispatcher.deliverDueEvents());
        assertEquals(1, dispatcher.deliverDueEvents());

        List<String> paths = supportService.received().stream()
                .map(RecordingSupportService.Received::path).toList();
        assertTrue(paths.contains(SupportOutboxEventType.SUPPORT_MESSAGE_EDITED.path()));
        assertTrue(paths.contains(SupportOutboxEventType.SUPPORT_MESSAGE_DELETED.path()));
        assertNotEquals(eventIdOf(supportService.received().get(1).body()),
                eventIdOf(supportService.received().get(2).body()),
                "each chat mutation is its own event with its own id");
    }

    @Test
    void aRolledBackChatMutationRecordsNoEvent() {
        ensureUser(MAIL);
        SupportMessageEntity legacy = new SupportMessageEntity();
        legacy.setUserMail(MAIL);
        legacy.setSender("MANAGEMENT");
        legacy.setMessage("legacy");
        supportMessageEntityRepository.saveAndFlush(legacy);

        // A legacy management message has no external id, so the admin delete
        // path fails after the row change is staged.
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> supportChatService.deleteMessageForAdmin(MAIL, legacy.getId()));
        assertTrue(failure.getMessage().contains("legacy"), failure.getMessage());

        assertEquals(0, pendingCount(),
                "an event for a rolled-back mutation would notify the support service about "
                        + "something that never happened");
    }

    @Test
    void theOutboxTableRejectsAnUnknownStatus() {
        sendUserMessage("guarded");
        assertThrows(RuntimeException.class, () -> jdbcTemplate.update(
                "UPDATE support_outbox_entity SET status = 'NOT_A_STATUS'"));
    }

    private void sendUserMessage(String message) {
        sendUserMessageFor(MAIL, message);
    }

    private void sendUserMessageFor(String mail, String message) {
        ensureUser(mail);
        supportChatService.addUserMessage(tokenService.generateUserToken(mail), message);
    }

    private void ensureUser(String mail) {
        if (userEntityRepository.findByUserMail(mail) != null) {
            return;
        }
        UserEntity user = new UserEntity();
        user.setUserMail(mail);
        user.setBanned(false);
        userEntityRepository.saveAndFlush(user);
    }

    private String eventIdOfOnlyRow() {
        return jdbcTemplate.queryForObject(
                "SELECT event_id FROM support_outbox_entity ORDER BY sequence_no LIMIT 1", String.class);
    }

    private String eventIdOf(String body) {
        return eventField(body, "eventId");
    }

    private String eventField(String body, String field) {
        try {
            JsonNode node = objectMapper.readTree(body);
            return node.has(field) ? node.get(field).asText() : null;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String storedPayloadOf(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT payload FROM support_outbox_entity WHERE event_id = ?", String.class, eventId);
    }

    private int pendingCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM support_outbox_entity WHERE status = 'PENDING'", Integer.class);
    }

    private int deliveredCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM support_outbox_entity WHERE status = 'DELIVERED'", Integer.class);
    }

    private SupportOutboxStatus statusOfOnlyRow() {
        return SupportOutboxStatus.valueOf(jdbcTemplate.queryForObject(
                "SELECT status FROM support_outbox_entity ORDER BY sequence_no LIMIT 1", String.class));
    }

    private int attemptCountOfOnlyRow() {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM support_outbox_entity ORDER BY sequence_no LIMIT 1", Integer.class);
    }

    private String lastErrorOfOnlyRow() {
        return jdbcTemplate.queryForObject(
                "SELECT last_error FROM support_outbox_entity ORDER BY sequence_no LIMIT 1", String.class);
    }

    private void expireLease(String eventId) {
        jdbcTemplate.update("UPDATE support_outbox_entity SET lease_expires_at = ? WHERE event_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusSeconds(1)), eventId);
    }

    private void makeNextAttemptDue() {
        jdbcTemplate.update("UPDATE support_outbox_entity SET next_attempt_at = ?",
                Timestamp.valueOf(LocalDateTime.now().minusSeconds(1)));
    }
}
