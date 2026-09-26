package com.example.MigrosBackend.service.user.payment;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.MigrosBackend.controller.user.payment.StripeWebhookController;
import com.example.MigrosBackend.entity.checkout.CheckoutEntity;
import com.example.MigrosBackend.entity.payment.PaymentAttemptEntity;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.user.WebhookSignatureException;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.CheckoutEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.PaymentAttemptEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * Crash-safety contract for the durable Stripe webhook inbox, proven against
 * real PostgreSQL (locks, claims, and redelivery — never Mockito alone).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class PaymentWebhookInboxPostgresTest {

    private static final String USER_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String ADMIN_SECRET = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";
    private static final String WEBHOOK_SECRET = "whsec_test_inbox_secret_0123456789";
    private static final String STRIPE_API_VERSION = "2025-02-24.acacia";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jwt.user-secret", () -> USER_SECRET);
        registry.add("jwt.admin-secret", () -> ADMIN_SECRET);
        registry.add("support.internal.key", () -> "integration-test-internal-key");
        registry.add("support.service.internal-key", () -> "integration-test-internal-key");
        registry.add("payment.checkout.expiration-scan-ms", () -> "86400000");
        registry.add("payment.checkout.expiration-initial-delay-ms", () -> "86400000");
        registry.add("payment.recovery.scan-ms", () -> "86400000");
        registry.add("payment.recovery.initial-delay-ms", () -> "86400000");
        registry.add("payment.webhook.secret", () -> WEBHOOK_SECRET);
        registry.add("payment.webhook.inbox-scan-ms", () -> "86400000");
        registry.add("payment.webhook.inbox-initial-delay-ms", () -> "86400000");
        registry.add("payment.webhook.inbox-cleanup-scan-ms", () -> "86400000");
        registry.add("payment.webhook.inbox-cleanup-initial-delay-ms", () -> "86400000");
        registry.add("payment.webhook.inbox-retention-days", () -> "30");
    }

    @SpyBean
    private PaymentWebhookService webhookService;
    @Autowired
    private StripeEventStore stripeEventStore;
    @Autowired
    private StripeWebhookRecoveryJob recoveryJob;
    @Autowired
    private StripeWebhookController controller;
    @Autowired
    private CheckoutService checkoutService;
    @Autowired
    private PaymentAttemptService paymentAttemptService;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private UserEntityRepository userEntityRepository;
    @Autowired
    private ProductEntityRepository productEntityRepository;
    @Autowired
    private CheckoutEntityRepository checkoutEntityRepository;
    @Autowired
    private PaymentAttemptEntityRepository paymentAttemptEntityRepository;
    @Autowired
    private OrderGroupEntityRepository orderGroupEntityRepository;
    @Autowired
    private OrderEntityRepository orderEntityRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        reset(webhookService);
        jdbcTemplate.execute("TRUNCATE TABLE stripe_event_entity, payment_attempt_entity, "
                + "checkout_item_entity, checkout_entity, order_entity, order_group_entity, "
                + "product_entity, user_entity RESTART IDENTITY CASCADE");
    }

    @Test
    void invalidSignatureCreatesNoInboxRow() {
        String payload = chargeSucceededPayload("evt_sig", "ch_sig", 1000L, "try", UUID.randomUUID());

        assertThrows(WebhookSignatureException.class,
                () -> controller.receive(payload, "t=1,v1=deadbeef"));
        assertEquals(0, inboxRowCount());
    }

    @Test
    void missingSignatureCreatesNoInboxRow() {
        String payload = chargeSucceededPayload("evt_nosig", "ch_nosig", 1000L, "try", UUID.randomUUID());

        assertThrows(WebhookSignatureException.class, () -> controller.receive(payload, null));
        assertEquals(0, inboxRowCount());
    }

    @Test
    void firstVerifiedDeliveryCreatesOneProcessedRow() throws Exception {
        AttemptFixture fixture = prepareProcessingAttempt();
        String payload = chargeSucceededPayload("evt_first", "ch_first",
                fixture.amountMinor(), "try", fixture.checkoutId());

        ResponseEntity<Map<String, Object>> response = controller.receive(payload, validSignature(payload));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("PROCESSED", rowStatus("evt_first"));
        assertEquals(1, attemptCount("evt_first"));
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, attemptStatus(fixture.checkoutId()));
        assertEquals(1, orderGroupCount());
    }

    @Test
    void crashAfterReceiptIsRecoveredByRedelivery() {
        AttemptFixture fixture = prepareProcessingAttempt();
        String payload = chargeSucceededPayload("evt_crash_receipt", "ch_crash_receipt",
                fixture.amountMinor(), "try", fixture.checkoutId());

        // A committed RECEIVED row with no processing ever running: the JVM
        // died after receipt. Redelivery must still apply the effects.
        stripeEventStore.receive("evt_crash_receipt", "charge.succeeded", payload,
                PaymentWebhookService.sha256Hex(payload), LocalDateTime.now());

        webhookService.handle(parse(payload), payload);

        assertEquals("PROCESSED", rowStatus("evt_crash_receipt"));
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, attemptStatus(fixture.checkoutId()));
        assertEquals(1, orderGroupCount());
    }

    @Test
    void crashAfterReceiptIsRecoveredByTheScheduledWorker() {
        AttemptFixture fixture = prepareProcessingAttempt();
        String payload = chargeSucceededPayload("evt_crash_worker", "ch_crash_worker",
                fixture.amountMinor(), "try", fixture.checkoutId());
        stripeEventStore.receive("evt_crash_worker", "charge.succeeded", payload,
                PaymentWebhookService.sha256Hex(payload), LocalDateTime.now());

        recoveryJob.recoverDueWebhooks();

        assertEquals("PROCESSED", rowStatus("evt_crash_worker"));
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, attemptStatus(fixture.checkoutId()));
        assertEquals(1, orderGroupCount());
    }

    @Test
    void crashAfterEffectsBeforeProcessedIsHarmlessOnRetry() {
        AttemptFixture fixture = prepareProcessingAttempt();
        String payload = chargeSucceededPayload("evt_crash_effects", "ch_crash_effects",
                fixture.amountMinor(), "try", fixture.checkoutId());

        doThrow(new RuntimeException("simulated crash after effects"))
                .when(webhookService).afterEffects(anyString());
        assertThrows(RuntimeException.class, () -> webhookService.handle(parse(payload), payload));

        // Effects committed exactly once, but completion was lost.
        assertEquals("FAILED", rowStatus("evt_crash_effects"));
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, attemptStatus(fixture.checkoutId()));
        assertEquals(1, orderGroupCount());

        // Backoff has not elapsed from the worker's point of view; simulate
        // time passing deterministically instead of sleeping.
        expireNextAttempt("evt_crash_effects");
        reset(webhookService);
        webhookService.handle(parse(payload), payload);

        assertEquals("PROCESSED", rowStatus("evt_crash_effects"));
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, attemptStatus(fixture.checkoutId()));
        assertEquals(1, orderGroupCount());
        assertEquals(1, orderCount());
    }

    @Test
    void concurrentDuplicateDeliveriesProduceExactlyOnceEffects() throws Exception {
        AttemptFixture fixture = prepareProcessingAttempt();
        String payload = chargeSucceededPayload("evt_concurrent", "ch_concurrent",
                fixture.amountMinor(), "try", fixture.checkoutId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> delivery = () -> {
            ready.countDown();
            assertTrue(start.await(30, TimeUnit.SECONDS));
            webhookService.handle(parse(payload), payload);
            return null;
        };
        try {
            Future<Void> first = pool.submit(delivery);
            Future<Void> second = pool.submit(delivery);
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, inboxRowCount());
        assertEquals("PROCESSED", rowStatus("evt_concurrent"));
        assertEquals(1, attemptCount("evt_concurrent"));
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, attemptStatus(fixture.checkoutId()));
        assertEquals(1, orderGroupCount());
        assertEquals(1, orderCount());
    }

    @Test
    void processedDuplicateReturnsWithoutDuplicatingEffects() {
        AttemptFixture fixture = prepareProcessingAttempt();
        String payload = chargeSucceededPayload("evt_redeliver", "ch_redeliver",
                fixture.amountMinor(), "try", fixture.checkoutId());
        webhookService.handle(parse(payload), payload);
        Timestamp completedAt = processedAt("evt_redeliver");

        webhookService.handle(parse(payload), payload);

        assertEquals("PROCESSED", rowStatus("evt_redeliver"));
        assertEquals(completedAt, processedAt("evt_redeliver"));
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, attemptStatus(fixture.checkoutId()));
        assertEquals(1, orderGroupCount());
    }

    @Test
    void eventIdCollisionWithDifferentContentFailsClosed() {
        AttemptFixture first = prepareProcessingAttempt();
        String original = chargeSucceededPayload("evt_collision", "ch_collision_a",
                first.amountMinor(), "try", first.checkoutId());
        webhookService.handle(parse(original), original);
        assertEquals("PROCESSED", rowStatus("evt_collision"));

        AttemptFixture second = prepareProcessingAttempt();
        String tampered = chargeSucceededPayload("evt_collision", "ch_collision_b",
                second.amountMinor(), "try", second.checkoutId());
        webhookService.handle(parse(tampered), tampered);

        assertEquals("MANUAL_REVIEW", rowStatus("evt_collision"));
        assertEquals("event_id_collision", lastError("evt_collision"));
        // No effects from the colliding delivery: one order, second attempt untouched.
        assertEquals(1, orderGroupCount());
        assertEquals(PaymentAttemptStatus.PROCESSING, attemptStatus(second.checkoutId()));
    }

    @Test
    void activeLeaseIsNotReclaimed() {
        AttemptFixture fixture = prepareProcessingAttempt();
        String payload = chargeSucceededPayload("evt_lease", "ch_lease",
                fixture.amountMinor(), "try", fixture.checkoutId());
        stripeEventStore.receive("evt_lease", "charge.succeeded", payload,
                PaymentWebhookService.sha256Hex(payload), LocalDateTime.now());
        assertTrue(stripeEventStore.tryClaim("evt_lease", "other-worker",
                LocalDateTime.now(), 3600).isPresent());

        webhookService.handle(parse(payload), payload);

        assertEquals("PROCESSING", rowStatus("evt_lease"));
        assertEquals(1, attemptCount("evt_lease"));
        assertEquals(PaymentAttemptStatus.PROCESSING, attemptStatus(fixture.checkoutId()));
        assertEquals(0, orderGroupCount());
    }

    @Test
    void expiredLeaseIsReclaimable() {
        AttemptFixture fixture = prepareProcessingAttempt();
        String payload = chargeSucceededPayload("evt_expired_lease", "ch_expired_lease",
                fixture.amountMinor(), "try", fixture.checkoutId());
        stripeEventStore.receive("evt_expired_lease", "charge.succeeded", payload,
                PaymentWebhookService.sha256Hex(payload), LocalDateTime.now());
        assertTrue(stripeEventStore.tryClaim("evt_expired_lease", "crashed-worker",
                LocalDateTime.now(), 3600).isPresent());
        expireLease("evt_expired_lease");

        webhookService.handle(parse(payload), payload);

        assertEquals("PROCESSED", rowStatus("evt_expired_lease"));
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, attemptStatus(fixture.checkoutId()));
        assertEquals(1, orderGroupCount());
    }

    @Test
    void retryableFailuresBackOffAndEventuallyProcess() {
        AttemptFixture fixture = prepareProcessingAttempt();
        String payload = chargeSucceededPayload("evt_retry", "ch_retry",
                fixture.amountMinor(), "try", fixture.checkoutId());

        doThrow(new RuntimeException("transient one"))
                .doThrow(new RuntimeException("transient two"))
                .doNothing()
                .when(webhookService).afterEffects(anyString());

        assertThrows(RuntimeException.class, () -> webhookService.handle(parse(payload), payload));
        LocalDateTime firstRetry = nextAttemptAt("evt_retry");
        expireNextAttempt("evt_retry");

        assertThrows(RuntimeException.class, () -> webhookService.handle(parse(payload), payload));
        LocalDateTime secondRetry = nextAttemptAt("evt_retry");
        assertTrue(secondRetry.isAfter(firstRetry), "backoff must grow between attempts");
        expireNextAttempt("evt_retry");

        webhookService.handle(parse(payload), payload);

        assertEquals("PROCESSED", rowStatus("evt_retry"));
        assertEquals(3, attemptCount("evt_retry"));
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, attemptStatus(fixture.checkoutId()));
        assertEquals(1, orderGroupCount());
    }

    @Test
    void exhaustedFailuresRemainVisibleForManualReview() {
        AttemptFixture fixture = prepareProcessingAttempt();
        String payload = chargeSucceededPayload("evt_exhausted", "ch_exhausted",
                fixture.amountMinor(), "try", fixture.checkoutId());
        stripeEventStore.receive("evt_exhausted", "charge.succeeded", payload,
                PaymentWebhookService.sha256Hex(payload), LocalDateTime.now());
        jdbcTemplate.update("UPDATE stripe_event_entity SET status = 'PROCESSING', attempt_count = 8, "
                + "lease_owner = 'crashed', lease_expires_at = now() - interval '1 minute' "
                + "WHERE event_id = 'evt_exhausted'");

        doThrow(new RuntimeException("terminal crash"))
                .when(webhookService).afterEffects(anyString());
        assertThrows(RuntimeException.class, () -> webhookService.handle(parse(payload), payload));

        assertEquals("MANUAL_REVIEW", rowStatus("evt_exhausted"));
        assertTrue(lastError("evt_exhausted").startsWith("exhausted:"));
    }

    @Test
    void refundAndDisputeEventsAreNotLostAndCannotRegress() {
        AttemptFixture first = prepareProcessingAttempt();
        String succeeded = chargeSucceededPayload("evt_ref_base", "ch_ref_base",
                first.amountMinor(), "try", first.checkoutId());
        webhookService.handle(parse(succeeded), succeeded);
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, attemptStatus(first.checkoutId()));

        String refunded = chargeRefundedPayload("evt_refund", "ch_ref_base",
                first.amountMinor(), "try", first.checkoutId());
        webhookService.handle(parse(refunded), refunded);
        assertEquals(PaymentAttemptStatus.REFUNDED, attemptStatus(first.checkoutId()));
        assertEquals("PROCESSED", rowStatus("evt_refund"));

        // A late success for the refunded charge must not regress the refund.
        String lateSuccess = chargeSucceededPayload("evt_ref_late", "ch_ref_base",
                first.amountMinor(), "try", first.checkoutId());
        webhookService.handle(parse(lateSuccess), lateSuccess);
        assertEquals(PaymentAttemptStatus.REFUNDED, attemptStatus(first.checkoutId()));
        assertEquals(1, orderGroupCount());

        AttemptFixture second = prepareProcessingAttempt();
        String secondSuccess = chargeSucceededPayload("evt_dis_base", "ch_dis_base",
                second.amountMinor(), "try", second.checkoutId());
        webhookService.handle(parse(secondSuccess), secondSuccess);

        String dispute = disputePayload("evt_dispute", "ch_dis_base");
        webhookService.handle(parse(dispute), dispute);
        assertEquals(PaymentAttemptStatus.MANUAL_REVIEW, attemptStatus(second.checkoutId()));
        assertEquals("PROCESSED", rowStatus("evt_dispute"));

        // A late success for a disputed attempt must not clear the review flag.
        String disputedLate = chargeSucceededPayload("evt_dis_late", "ch_dis_base",
                second.amountMinor(), "try", second.checkoutId());
        webhookService.handle(parse(disputedLate), disputedLate);
        assertEquals(PaymentAttemptStatus.MANUAL_REVIEW, attemptStatus(second.checkoutId()));
    }

    @Test
    void outOfOrderFailureCannotRegressAFinalizedAttempt() {
        AttemptFixture fixture = prepareProcessingAttempt();
        String succeeded = chargeSucceededPayload("evt_ooo_base", "ch_ooo",
                fixture.amountMinor(), "try", fixture.checkoutId());
        webhookService.handle(parse(succeeded), succeeded);

        String failed = chargeFailedPayload("evt_ooo_failed", "ch_ooo",
                fixture.amountMinor(), "try", fixture.checkoutId());
        webhookService.handle(parse(failed), failed);

        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, attemptStatus(fixture.checkoutId()));
        assertEquals("PROCESSED", rowStatus("evt_ooo_failed"));
        assertEquals(1, orderGroupCount());
    }

    @Test
    void unhandledEventTypesCompleteWithoutEffects() {
        String payload = "{\"id\":\"evt_ping\",\"object\":\"event\",\"api_version\":\""
                + STRIPE_API_VERSION + "\",\"type\":\"ping\",\"data\":{\"object\":{}}}";

        webhookService.handle(parse(payload), payload);

        assertEquals("PROCESSED", rowStatus("evt_ping"));
        assertEquals(0, orderGroupCount());
    }

    @Test
    void retentionRemovesOnlySufficientlyOldProcessedEvents() {
        AttemptFixture oldFixture = prepareProcessingAttempt();
        String oldPayload = chargeSucceededPayload("evt_ret_old", "ch_ret_old",
                oldFixture.amountMinor(), "try", oldFixture.checkoutId());
        webhookService.handle(parse(oldPayload), oldPayload);
        jdbcTemplate.update("UPDATE stripe_event_entity SET processed_at = now() - interval '40 days' "
                + "WHERE event_id = 'evt_ret_old'");

        AttemptFixture newFixture = prepareProcessingAttempt();
        String newPayload = chargeSucceededPayload("evt_ret_new", "ch_ret_new",
                newFixture.amountMinor(), "try", newFixture.checkoutId());
        webhookService.handle(parse(newPayload), newPayload);

        String receivedPayload = chargeSucceededPayload("evt_ret_recv", "ch_ret_recv",
                1000L, "try", UUID.randomUUID());
        stripeEventStore.receive("evt_ret_recv", "charge.succeeded", receivedPayload,
                PaymentWebhookService.sha256Hex(receivedPayload), LocalDateTime.now());
        jdbcTemplate.update("UPDATE stripe_event_entity SET received_at = now() - interval '40 days', "
                + "next_attempt_at = now() - interval '40 days' WHERE event_id = 'evt_ret_recv'");

        recoveryJob.purgeProcessedWebhooks();

        assertEquals(0, countRows("evt_ret_old"));
        assertEquals(1, countRows("evt_ret_new"));
        assertEquals(1, countRows("evt_ret_recv"));
    }

    @Test
    void failureCodesAreSanitizedAndPayloadsNeverLogged() {
        Logger logger = (Logger) LoggerFactory.getLogger(PaymentWebhookService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            AttemptFixture fixture = prepareProcessingAttempt();
            String marker = "SECRET_MARKER_9f8b7a crisps";
            String payload = "{\"id\":\"evt_secret\",\"object\":\"event\",\"api_version\":\""
                    + STRIPE_API_VERSION + "\",\"type\":\"charge.succeeded\",\"note\":\"" + marker + "\","
                    + "\"data\":{\"object\":{\"id\":\"ch_secret\",\"object\":\"charge\","
                    + "\"amount\":" + fixture.amountMinor() + ",\"currency\":\"try\",\"paid\":true,"
                    + "\"metadata\":{\"checkout_id\":\"" + fixture.checkoutId() + "\"}}}}";

            doThrow(new RuntimeException("boom")).when(webhookService).afterEffects(anyString());
            assertThrows(RuntimeException.class, () -> webhookService.handle(parse(payload), payload));

            for (ILoggingEvent logged : appender.list) {
                assertFalse(logged.getFormattedMessage().contains(marker),
                        "payload must never enter logs: " + logged.getFormattedMessage());
                assertFalse(logged.getFormattedMessage().contains(WEBHOOK_SECRET),
                        "secrets must never enter logs");
            }
            String error = lastError("evt_secret");
            assertTrue(error.matches("[A-Za-z0-9:_.-]{1,64}"), "error codes must be sanitized");
            assertFalse(error.contains(marker));
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ------------------------------------------------------------------ helpers

    private record AttemptFixture(UUID checkoutId, long amountMinor) {
    }

    private AttemptFixture prepareProcessingAttempt() {
        ProductEntity product = createProduct("10.00", 5);
        UserEntity user = createUser("buyer-" + UUID.randomUUID() + "@migros.com", product.getId());
        String token = tokenService.generateUserToken(user.getUserMail());
        UUID checkoutId = UUID.fromString(checkoutService.prepareCheckout(token).checkoutId());
        checkoutService.beginPayment(token, checkoutId);
        CheckoutEntity checkout = checkoutEntityRepository.findById(checkoutId).orElseThrow();
        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setId(UUID.randomUUID());
        attempt.setCheckout(checkout);
        attempt.setIdempotencyKey("checkout:" + checkoutId + ":charge-v1");
        attempt.setAmountMinor(checkout.getAmountMinor());
        attempt.setCurrency(checkout.getCurrency());
        attempt.setStatus(PaymentAttemptStatus.PROCESSING);
        attempt.setCreatedAt(LocalDateTime.now());
        attempt.setUpdatedAt(LocalDateTime.now());
        paymentAttemptEntityRepository.saveAndFlush(attempt);
        return new AttemptFixture(checkoutId, checkout.getAmountMinor());
    }

    private Event parse(String payload) {
        return StripeObject.deserializeStripeObject(
                payload, Event.class, ApiResource.getGlobalResponseGetter());
    }

    private String chargeSucceededPayload(String eventId, String chargeId, long amountMinor,
                                           String currency, UUID checkoutId) {
        return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"api_version\":\""
                + STRIPE_API_VERSION + "\",\"type\":\"charge.succeeded\","
                + "\"data\":{\"object\":{\"id\":\"" + chargeId + "\",\"object\":\"charge\","
                + "\"amount\":" + amountMinor + ",\"currency\":\"" + currency + "\",\"paid\":true,"
                + "\"metadata\":{\"checkout_id\":\"" + checkoutId + "\"}}}}";
    }

    private String chargeFailedPayload(String eventId, String chargeId, long amountMinor,
                                        String currency, UUID checkoutId) {
        return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"api_version\":\""
                + STRIPE_API_VERSION + "\",\"type\":\"charge.failed\","
                + "\"data\":{\"object\":{\"id\":\"" + chargeId + "\",\"object\":\"charge\","
                + "\"amount\":" + amountMinor + ",\"currency\":\"" + currency + "\",\"paid\":false,"
                + "\"failure_code\":\"card_declined\","
                + "\"metadata\":{\"checkout_id\":\"" + checkoutId + "\"}}}}";
    }

    private String chargeRefundedPayload(String eventId, String chargeId, long amountMinor,
                                          String currency, UUID checkoutId) {
        return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"api_version\":\""
                + STRIPE_API_VERSION + "\",\"type\":\"charge.refunded\","
                + "\"data\":{\"object\":{\"id\":\"" + chargeId + "\",\"object\":\"charge\","
                + "\"amount\":" + amountMinor + ",\"currency\":\"" + currency + "\",\"paid\":true,"
                + "\"refunded\":true,"
                + "\"metadata\":{\"checkout_id\":\"" + checkoutId + "\"}}}}";
    }

    private String disputePayload(String eventId, String chargeId) {
        return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"api_version\":\""
                + STRIPE_API_VERSION + "\",\"type\":\"charge.dispute.created\","
                + "\"data\":{\"object\":{\"id\":\"dp_1\",\"object\":\"dispute\",\"charge\":\""
                + chargeId + "\"}}}";
    }

    private String validSignature(String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signature = mac.doFinal(
                (timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(signature);
    }

    private PaymentAttemptStatus attemptStatus(UUID checkoutId) {
        return paymentAttemptEntityRepository.findByCheckoutId(checkoutId)
                .orElseThrow().getStatus();
    }

    private String rowStatus(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM stripe_event_entity WHERE event_id = ?", String.class, eventId);
    }

    private int attemptCount(String eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM stripe_event_entity WHERE event_id = ?", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private String lastError(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_error FROM stripe_event_entity WHERE event_id = ?", String.class, eventId);
    }

    private LocalDateTime nextAttemptAt(String eventId) {
        Timestamp timestamp = jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM stripe_event_entity WHERE event_id = ?",
                Timestamp.class, eventId);
        assertTrue(timestamp != null, "FAILED rows must schedule a next attempt");
        return timestamp.toLocalDateTime();
    }

    private Timestamp processedAt(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT processed_at FROM stripe_event_entity WHERE event_id = ?",
                Timestamp.class, eventId);
    }

    private int inboxRowCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stripe_event_entity", Integer.class);
        return count == null ? 0 : count;
    }

    private int countRows(String eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stripe_event_entity WHERE event_id = ?", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private int orderGroupCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_group_entity", Integer.class);
        return count == null ? 0 : count;
    }

    private int orderCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_entity", Integer.class);
        return count == null ? 0 : count;
    }

    private void expireNextAttempt(String eventId) {
        jdbcTemplate.update("UPDATE stripe_event_entity SET next_attempt_at = now() - interval '1 second' "
                + "WHERE event_id = ?", eventId);
    }

    private void expireLease(String eventId) {
        jdbcTemplate.update("UPDATE stripe_event_entity SET lease_expires_at = now() - interval '1 second' "
                + "WHERE event_id = ?", eventId);
    }

    private UserEntity createUser(String mail, Long... productIds) {
        UserEntity user = new UserEntity();
        user.setUserMail(mail);
        user.setBanned(false);
        user.setProductsIdsInCart(new ArrayList<>(java.util.Arrays.asList(productIds)));
        return userEntityRepository.saveAndFlush(user);
    }

    private ProductEntity createProduct(String price, int stock) {
        ProductEntity product = new ProductEntity();
        product.setProductName("Product");
        product.setSubcategoryName("general");
        product.setProductCount(stock);
        product.setProductPrice(new BigDecimal(price));
        product.setProductDiscount(BigDecimal.ZERO);
        product.setProductDescription("test product");
        return productEntityRepository.saveAndFlush(product);
    }
}
