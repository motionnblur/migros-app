package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.CheckoutResponseDto;
import com.example.MigrosBackend.dto.payment.PaymentClaim;
import com.example.MigrosBackend.dto.payment.PaymentClaimDecision;
import com.example.MigrosBackend.dto.payment.PaymentResponseDto;
import com.example.MigrosBackend.entity.payment.PaymentAttemptEntity;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.user.PaymentStateException;
import com.example.MigrosBackend.exception.user.StalePaymentLeaseException;
import com.example.MigrosBackend.helper.ProductPricingPolicy;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.PaymentAttemptEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PostgreSQL coverage for payment-attempt lease fencing: the lease owner is a
 * fencing token, and only the worker holding the current token may apply
 * request-worker results. Webhooks and reconciliation use the separate
 * provider-event path with full economic verification instead of a token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class PaymentLeaseFencingPostgresTest {

    private static final String USER_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String ADMIN_SECRET = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";

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
    }

    @Autowired
    private CheckoutService checkoutService;
    @Autowired
    private UserPaymentService userPaymentService;
    @Autowired
    private PaymentRecoveryService paymentRecoveryService;
    @Autowired
    private PaymentAttemptService paymentAttemptService;
    @Autowired
    private PaymentWebhookService paymentWebhookService;
    @Autowired
    private PaymentFinalizationService paymentFinalizationService;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private UserEntityRepository userEntityRepository;
    @Autowired
    private ProductEntityRepository productEntityRepository;
    @Autowired
    private PaymentAttemptEntityRepository paymentAttemptEntityRepository;
    @Autowired
    private OrderGroupEntityRepository orderGroupEntityRepository;
    @Autowired
    private OrderEntityRepository orderEntityRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private StripePaymentGateway stripePaymentGateway;

    private record Fixture(UserEntity user, String token, UUID checkoutId, UUID attemptId,
                           String lease, Long productId) {
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE stripe_event_entity, payment_attempt_entity, "
                + "checkout_item_entity, checkout_entity, order_entity, order_group_entity, "
                + "product_entity, user_entity RESTART IDENTITY CASCADE");
        try {
            when(stripePaymentGateway.findChargeForCheckout(anyString())).thenReturn(Optional.empty());
        } catch (StripeException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void currentLeaseOwnerRecordsSuccessAndFinalizes() throws Exception {
        Fixture fixture = successFixture("owner@migros.com", "ch_current");

        PaymentAttemptEntity attempt = reload(fixture.attemptId());
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, attempt.getStatus());
        assertEquals("ch_current", attempt.getStripeChargeId());
        assertEquals(1, orderGroupEntityRepository.findAll().size());
        assertEquals(1, orderEntityRepository.findAll().size());
    }

    @Test
    void currentLeaseOwnerRecordsDeclineAndReleasesStockOnce() throws Exception {
        Fixture fixture = ambiguousFixture("decline@migros.com");

        paymentAttemptService.recordDecline(fixture.attemptId(), fixture.lease(), "card_declined");
        // A repeated decline is an idempotent no-op, never a second release.
        paymentAttemptService.recordDecline(fixture.attemptId(), fixture.lease(), "card_declined");

        assertEquals(PaymentAttemptStatus.FAILED_FINAL, reload(fixture.attemptId()).getStatus());
        assertEquals(5, stockOf(fixture.productId()),
                "a provider-confirmed decline must release the reservation exactly once");
        assertEquals("CANCELLED", checkoutService.getCheckout(fixture.token(), fixture.checkoutId()).status());
    }

    @Test
    void incorrectNullOrBlankWorkerTokenIsRejected() throws Exception {
        Fixture fixture = ambiguousFixture("tokens@migros.com");

        assertThrows(StalePaymentLeaseException.class,
                () -> paymentAttemptService.recordChargeSuccess(fixture.attemptId(), null, "ch_x"));
        assertThrows(StalePaymentLeaseException.class,
                () -> paymentAttemptService.recordChargeSuccess(fixture.attemptId(), "   ", "ch_x"));
        assertThrows(StalePaymentLeaseException.class,
                () -> paymentAttemptService.recordChargeSuccess(fixture.attemptId(), "wrong-owner", "ch_x"));
        assertThrows(StalePaymentLeaseException.class,
                () -> paymentAttemptService.recordDecline(fixture.attemptId(), null, "card_declined"));
        assertThrows(StalePaymentLeaseException.class,
                () -> paymentAttemptService.recordDecline(fixture.attemptId(), "wrong-owner", "card_declined"));
        assertThrows(StalePaymentLeaseException.class,
                () -> paymentAttemptService.markOrderFinalized(fixture.attemptId(), null));

        PaymentAttemptEntity attempt = reload(fixture.attemptId());
        assertEquals(PaymentAttemptStatus.PROCESSING, attempt.getStatus());
        assertEquals(null, attempt.getStripeChargeId());
        assertEquals(fixture.lease(), attempt.getLeaseOwner());
        assertEquals(4, stockOf(fixture.productId()));
        assertEquals("PAYMENT_PROCESSING",
                checkoutService.getCheckout(fixture.token(), fixture.checkoutId()).status());
    }

    @Test
    void staleWorkerCannotRecordSuccessAfterReclaim() throws Exception {
        Fixture fixture = ambiguousFixture("stale-success@migros.com");
        PaymentClaim reclaim = reclaim(fixture);
        assertEquals(PaymentClaimDecision.PROCEED, reclaim.decision());

        assertThrows(StalePaymentLeaseException.class,
                () -> paymentAttemptService.recordChargeSuccess(
                        fixture.attemptId(), fixture.lease(), "ch_stale"));

        PaymentAttemptEntity attempt = reload(fixture.attemptId());
        assertEquals(PaymentAttemptStatus.PROCESSING, attempt.getStatus());
        assertEquals(null, attempt.getStripeChargeId());
        assertEquals(reclaim.leaseOwner(), storedLease(fixture.attemptId()));

        // The new owner still records success exactly once.
        paymentAttemptService.recordChargeSuccess(
                fixture.attemptId(), reclaim.leaseOwner(), "ch_fresh");
        assertEquals("ch_fresh", reload(fixture.attemptId()).getStripeChargeId());
    }

    @Test
    void staleDeclineCannotClearLeaseCancelCheckoutOrReleaseStock() throws Exception {
        Fixture fixture = ambiguousFixture("stale-decline@migros.com");
        PaymentClaim reclaim = reclaim(fixture);

        assertThrows(StalePaymentLeaseException.class,
                () -> paymentAttemptService.recordDecline(
                        fixture.attemptId(), fixture.lease(), "card_declined"));

        PaymentAttemptEntity attempt = reload(fixture.attemptId());
        assertEquals(PaymentAttemptStatus.PROCESSING, attempt.getStatus());
        assertEquals(reclaim.leaseOwner(), attempt.getLeaseOwner(),
                "a stale decline must not clear the current lease");
        assertEquals("PAYMENT_PROCESSING",
                checkoutService.getCheckout(fixture.token(), fixture.checkoutId()).status());
        assertEquals(4, stockOf(fixture.productId()),
                "a stale decline must not release stock");
    }

    @Test
    void staleWorkerCannotMarkFinalizationComplete() throws Exception {
        Fixture fixture = ambiguousFixture("stale-finalize@migros.com");
        PaymentClaim chargeLease = reclaim(fixture);
        paymentAttemptService.recordChargeSuccess(
                fixture.attemptId(), chargeLease.leaseOwner(), "ch_final");
        assertEquals(PaymentAttemptStatus.CHARGE_SUCCEEDED, reload(fixture.attemptId()).getStatus());

        expireLease(fixture.attemptId());
        PaymentClaim finalizeLease = paymentAttemptService.claim(
                fixture.token(), fixture.checkoutId(), ChargeIdempotencyKeys.forCheckout(fixture.checkoutId()));
        assertEquals(PaymentClaimDecision.FINALIZE, finalizeLease.decision());
        assertNotNull(finalizeLease.leaseOwner());

        assertThrows(StalePaymentLeaseException.class,
                () -> paymentAttemptService.markOrderFinalized(
                        fixture.attemptId(), chargeLease.leaseOwner()));
        assertEquals(PaymentAttemptStatus.CHARGE_SUCCEEDED, reload(fixture.attemptId()).getStatus());

        paymentAttemptService.markOrderFinalized(fixture.attemptId(), finalizeLease.leaseOwner());
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, reload(fixture.attemptId()).getStatus());
    }

    @Test
    void twoWorkersRacingAtLeaseBoundaryProduceOneAuthoritativeResult() throws Exception {
        Fixture fixture = ambiguousFixture("race@migros.com");
        PaymentClaim reclaim = reclaim(fixture);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<PaymentStateException> staleOutcome = pool.submit(() -> {
                gate.await(30, TimeUnit.SECONDS);
                try {
                    paymentAttemptService.recordChargeSuccess(
                            fixture.attemptId(), fixture.lease(), "ch_stale");
                    return null;
                } catch (StalePaymentLeaseException expected) {
                    return expected;
                }
            });
            Future<?> freshOutcome = pool.submit(() -> {
                gate.await(30, TimeUnit.SECONDS);
                paymentAttemptService.recordChargeSuccess(
                        fixture.attemptId(), reclaim.leaseOwner(), "ch_fresh");
                return null;
            });

            gate.countDown();
            assertNotNull(staleOutcome.get(30, TimeUnit.SECONDS),
                    "the replaced worker must be fenced out");
            freshOutcome.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        PaymentAttemptEntity attempt = reload(fixture.attemptId());
        assertEquals(PaymentAttemptStatus.CHARGE_SUCCEEDED, attempt.getStatus());
        assertEquals("ch_fresh", attempt.getStripeChargeId());
        assertEquals(reclaim.leaseOwner(), attempt.getLeaseOwner());
    }

    @Test
    void verifiedWebhookRecordsMatchingSuccessWithoutWorkerToken() {
        Fixture fixture = ambiguousFixture("webhook@migros.com");
        String eventId = "evt_fencing_ok";
        String chargeId = "ch_webhook_ok";
        String payload = chargeSucceededPayload(eventId, chargeId, 1000L, "try", fixture.checkoutId());
        Event event = deserializeEvent(payload);

        paymentWebhookService.handle(event, payload);

        PaymentAttemptEntity attempt = reload(fixture.attemptId());
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, attempt.getStatus());
        assertEquals(chargeId, attempt.getStripeChargeId());
        assertEquals(1, orderGroupEntityRepository.findAll().size());

        // Redelivery converges without a second order.
        paymentWebhookService.handle(event, payload);
        assertEquals(1, orderGroupEntityRepository.findAll().size());
    }

    @Test
    void providerChargeWithMismatchedEconomicsFailsClosed() throws Exception {
        Fixture amount = ambiguousFixture("mismatch-amount@migros.com");
        assertThrows(PaymentStateException.class,
                () -> paymentAttemptService.recordProviderChargeSuccess(
                        amount.attemptId(), "ch_bad_amount", 42L, "try", amount.checkoutId()));
        assertEquals(PaymentAttemptStatus.MANUAL_REVIEW, reload(amount.attemptId()).getStatus());

        Fixture currency = ambiguousFixture("mismatch-currency@migros.com");
        assertThrows(PaymentStateException.class,
                () -> paymentAttemptService.recordProviderChargeSuccess(
                        currency.attemptId(), "ch_bad_ccy", 1000L, "usd", currency.checkoutId()));
        assertEquals(PaymentAttemptStatus.MANUAL_REVIEW, reload(currency.attemptId()).getStatus());

        Fixture linkage = ambiguousFixture("mismatch-linkage@migros.com");
        assertThrows(PaymentStateException.class,
                () -> paymentAttemptService.recordProviderChargeSuccess(
                        linkage.attemptId(), "ch_bad_link", 1000L, "try", UUID.randomUUID()));
        assertEquals(PaymentAttemptStatus.MANUAL_REVIEW, reload(linkage.attemptId()).getStatus());
    }

    @Test
    void providerChargeWithConflictingChargeIdNeverOverwrites() throws Exception {
        Fixture fixture = successFixture("conflict@migros.com", "ch_first");
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, reload(fixture.attemptId()).getStatus());

        Fixture succeeded = ambiguousFixture("conflict-processing@migros.com");
        PaymentClaim lease = reclaim(succeeded);
        paymentAttemptService.recordChargeSuccess(succeeded.attemptId(), lease.leaseOwner(), "ch_one");
        assertThrows(PaymentStateException.class,
                () -> paymentAttemptService.recordProviderChargeSuccess(
                        succeeded.attemptId(), "ch_two", 1000L, "try", succeeded.checkoutId()));

        PaymentAttemptEntity attempt = reload(succeeded.attemptId());
        assertEquals(PaymentAttemptStatus.MANUAL_REVIEW, attempt.getStatus());
        assertEquals("ch_one", attempt.getStripeChargeId());
    }

    @Test
    void reconciliationRecoversAbandonedAttemptButNotNewerState() throws Exception {
        Fixture abandoned = ambiguousFixture("recover@migros.com");
        expireLease(abandoned.attemptId());
        Charge recovered = paidCharge("ch_recovered", 1000L, "try", abandoned.checkoutId().toString());
        when(stripePaymentGateway.findChargeForCheckout(abandoned.checkoutId().toString()))
                .thenReturn(Optional.of(recovered));

        paymentRecoveryService.recover(abandoned.attemptId());

        PaymentAttemptEntity attempt = reload(abandoned.attemptId());
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, attempt.getStatus());
        assertEquals("ch_recovered", attempt.getStripeChargeId());
        verify(stripePaymentGateway, times(1)).findChargeForCheckout(abandoned.checkoutId().toString());

        // Newer durable states are never overwritten by recovery.
        Fixture finalized = successFixture("recover-newer@migros.com", "ch_newer");
        paymentRecoveryService.recover(finalized.attemptId());
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, reload(finalized.attemptId()).getStatus());
        assertEquals("ch_newer", reload(finalized.attemptId()).getStripeChargeId());

        Fixture declined = ambiguousFixture("recover-declined@migros.com");
        PaymentClaim lease = reclaim(declined);
        paymentAttemptService.recordDecline(declined.attemptId(), lease.leaseOwner(), "card_declined");
        paymentRecoveryService.recover(declined.attemptId());
        assertEquals(PaymentAttemptStatus.FAILED_FINAL, reload(declined.attemptId()).getStatus());
        assertEquals(5, stockOf(declined.productId()));
    }

    @Test
    void lockOrderConcurrencyCompletesWithoutDeadlock() throws Exception {
        Fixture fixture = ambiguousFixture("lockorder@migros.com");
        String key = ChargeIdempotencyKeys.forCheckout(fixture.checkoutId());

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                futures.add(pool.submit(() -> {
                    for (int j = 0; j < 20; j++) {
                        paymentAttemptService.claim(fixture.token(), fixture.checkoutId(), key);
                    }
                    return null;
                }));
            }
            for (int i = 0; i < 3; i++) {
                futures.add(pool.submit(() -> {
                    for (int j = 0; j < 20; j++) {
                        paymentAttemptService.getStatus(fixture.token(), fixture.checkoutId());
                    }
                    return null;
                }));
            }
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    for (int j = 0; j < 20; j++) {
                        try {
                            paymentAttemptService.recordDecline(
                                    fixture.attemptId(), "wrong-owner", "card_declined");
                        } catch (StalePaymentLeaseException expected) {
                            // Expected: checkout-first locking, then fencing.
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        PaymentAttemptEntity attempt = reload(fixture.attemptId());
        assertEquals(PaymentAttemptStatus.PROCESSING, attempt.getStatus());
        assertEquals(fixture.lease(), attempt.getLeaseOwner());
        assertEquals(4, stockOf(fixture.productId()));
        assertEquals("PAYMENT_PROCESSING",
                checkoutService.getCheckout(fixture.token(), fixture.checkoutId()).status());
    }

    @Test
    void reclaimRetryReusesSameIdempotencyKeyAmountAndCurrency() throws Exception {
        ProductEntity product = createProduct("10.00", 5);
        UserEntity user = createUser("reclaim-retry@migros.com", product.getId());
        String token = token(user);
        UUID checkoutId = UUID.fromString(checkoutService.prepareCheckout(token).checkoutId());
        String key = ChargeIdempotencyKeys.forCheckout(checkoutId);

        when(stripePaymentGateway.charge(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new ApiConnectionException("timeout"))
                .thenAnswer(invocation -> paidCharge("ch_reclaim_retry",
                        invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(4)));

        PaymentResponseDto ambiguous = userPaymentService.processCharge(checkoutId, "tok_visa", token);
        assertTrue(ambiguous.pending());
        UUID attemptId = paymentAttemptEntityRepository.findByCheckoutId(checkoutId).orElseThrow().getId();
        String oldLease = storedLease(attemptId);

        expireLease(attemptId);
        PaymentResponseDto retried = userPaymentService.processCharge(checkoutId, "tok_visa", token);
        assertTrue(retried.success());

        ArgumentCaptor<Long> amount = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> currency = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> idempotency = ArgumentCaptor.forClass(String.class);
        verify(stripePaymentGateway, times(2)).charge(anyString(), amount.capture(),
                currency.capture(), idempotency.capture(), eq(checkoutId.toString()));
        assertEquals(amount.getAllValues().get(0), amount.getAllValues().get(1));
        assertEquals(currency.getAllValues().get(0), currency.getAllValues().get(1));
        assertEquals(key, idempotency.getAllValues().get(0));
        assertEquals(key, idempotency.getAllValues().get(1));

        // The replaced worker token no longer has any authority.
        assertThrows(StalePaymentLeaseException.class,
                () -> paymentAttemptService.recordChargeSuccess(attemptId, oldLease, "ch_stale"));
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, reload(attemptId).getStatus());
    }

    @Test
    void staleWorkerFinalizationViaServiceChangesNothing() {
        Fixture fixture = ambiguousFixture("svc-stale@migros.com");
        PaymentClaim chargeLease = reclaim(fixture);
        paymentAttemptService.recordChargeSuccess(
                fixture.attemptId(), chargeLease.leaseOwner(), "ch_svc_stale");
        String staleLease = chargeLease.leaseOwner();
        int stockBefore = stockOf(fixture.productId());

        expireLease(fixture.attemptId());
        PaymentClaim freshLease = paymentAttemptService.claim(
                fixture.token(), fixture.checkoutId(),
                ChargeIdempotencyKeys.forCheckout(fixture.checkoutId()));
        assertEquals(PaymentClaimDecision.FINALIZE, freshLease.decision());

        boolean finalized = paymentFinalizationService.finalizeOrder(
                fixture.attemptId(), fixture.checkoutId(), "ch_svc_stale", staleLease);

        assertFalse(finalized, "a stale worker must be fenced out before any side effect");
        PaymentAttemptEntity attempt = reload(fixture.attemptId());
        assertEquals(PaymentAttemptStatus.CHARGE_SUCCEEDED, attempt.getStatus());
        assertEquals("ch_svc_stale", attempt.getStripeChargeId());
        assertEquals(freshLease.leaseOwner(), attempt.getLeaseOwner(),
                "a stale worker must not clear or overwrite the current lease");
        assertEquals("PAYMENT_PROCESSING",
                checkoutService.getCheckout(fixture.token(), fixture.checkoutId()).status());
        assertTrue(orderGroupEntityRepository.findAll().isEmpty());
        assertTrue(orderEntityRepository.findAll().isEmpty());
        assertEquals(stockBefore, stockOf(fixture.productId()));
    }

    @Test
    void nullAndBlankWorkerTokenFinalizationChangesNothing() {
        Fixture fixture = ambiguousFixture("svc-tokens@migros.com");
        PaymentClaim chargeLease = reclaim(fixture);
        paymentAttemptService.recordChargeSuccess(
                fixture.attemptId(), chargeLease.leaseOwner(), "ch_svc_tokens");
        String currentLease = storedLease(fixture.attemptId());
        int stockBefore = stockOf(fixture.productId());

        assertThrows(PaymentStateException.class, () -> paymentFinalizationService.finalizeOrder(
                fixture.attemptId(), fixture.checkoutId(), "ch_svc_tokens", null));
        assertThrows(PaymentStateException.class, () -> paymentFinalizationService.finalizeOrder(
                fixture.attemptId(), fixture.checkoutId(), "ch_svc_tokens", "   "));

        PaymentAttemptEntity attempt = reload(fixture.attemptId());
        assertEquals(PaymentAttemptStatus.CHARGE_SUCCEEDED, attempt.getStatus());
        assertEquals(currentLease, attempt.getLeaseOwner());
        assertEquals("PAYMENT_PROCESSING",
                checkoutService.getCheckout(fixture.token(), fixture.checkoutId()).status());
        assertTrue(orderGroupEntityRepository.findAll().isEmpty());
        assertEquals(stockBefore, stockOf(fixture.productId()));
    }

    @Test
    void currentWorkerTokenFinalizesExactlyOnce() {
        Fixture fixture = ambiguousFixture("svc-current@migros.com");
        PaymentClaim chargeLease = reclaim(fixture);
        paymentAttemptService.recordChargeSuccess(
                fixture.attemptId(), chargeLease.leaseOwner(), "ch_svc_current");
        expireLease(fixture.attemptId());
        PaymentClaim finalizeLease = paymentAttemptService.claim(
                fixture.token(), fixture.checkoutId(),
                ChargeIdempotencyKeys.forCheckout(fixture.checkoutId()));

        assertTrue(paymentFinalizationService.finalizeOrder(
                fixture.attemptId(), fixture.checkoutId(), "ch_svc_current",
                finalizeLease.leaseOwner()));
        // Idempotent replay converges without a second order.
        assertTrue(paymentFinalizationService.finalizeOrder(
                fixture.attemptId(), fixture.checkoutId(), "ch_svc_current",
                finalizeLease.leaseOwner()));

        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, reload(fixture.attemptId()).getStatus());
        assertEquals("CONSUMED",
                checkoutService.getCheckout(fixture.token(), fixture.checkoutId()).status());
        assertEquals(1, orderGroupEntityRepository.findAll().size());
        assertEquals(1, orderEntityRepository.findAll().size());
    }

    @Test
    void twoWorkersFinalizingConvergeOnOneOrderWithoutDeadlock() throws Exception {
        Fixture fixture = ambiguousFixture("svc-race@migros.com");
        PaymentClaim chargeLease = reclaim(fixture);
        paymentAttemptService.recordChargeSuccess(
                fixture.attemptId(), chargeLease.leaseOwner(), "ch_svc_race");
        String firstLease = chargeLease.leaseOwner();
        expireLease(fixture.attemptId());
        PaymentClaim secondLease = paymentAttemptService.claim(
                fixture.token(), fixture.checkoutId(),
                ChargeIdempotencyKeys.forCheckout(fixture.checkoutId()));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<Boolean> staleOutcome = pool.submit(() -> {
                gate.await(30, TimeUnit.SECONDS);
                return paymentFinalizationService.finalizeOrder(
                        fixture.attemptId(), fixture.checkoutId(), "ch_svc_race", firstLease);
            });
            Future<Boolean> freshOutcome = pool.submit(() -> {
                gate.await(30, TimeUnit.SECONDS);
                return paymentFinalizationService.finalizeOrder(
                        fixture.attemptId(), fixture.checkoutId(), "ch_svc_race",
                        secondLease.leaseOwner());
            });
            gate.countDown();
            boolean staleResult = staleOutcome.get(30, TimeUnit.SECONDS);
            boolean freshResult = freshOutcome.get(30, TimeUnit.SECONDS);
            assertTrue(staleResult != freshResult || (staleResult && freshResult),
                    "exactly one worker must win, or the loser must converge idempotently");
            assertFalse(!staleResult && !freshResult, "at least one finalization must succeed");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, reload(fixture.attemptId()).getStatus());
        assertEquals(1, orderGroupEntityRepository.findAll().size());
        assertEquals(1, orderEntityRepository.findAll().size());
    }

    @Test
    void finalizationFailureRollsBackOrderAndAttemptTogether() {
        Fixture fixture = ambiguousFixture("svc-rollback@migros.com");
        PaymentClaim chargeLease = reclaim(fixture);
        paymentAttemptService.recordChargeSuccess(
                fixture.attemptId(), chargeLease.leaseOwner(), "ch_svc_rollback");
        // Break the checkout snapshot so order creation fails after the lease
        // fence has passed: the attempt transition must roll back with it.
        jdbcTemplate.update("DELETE FROM checkout_item_entity WHERE checkout_id = ?",
                fixture.checkoutId());

        boolean finalized = paymentFinalizationService.finalizeOrder(
                fixture.attemptId(), fixture.checkoutId(), "ch_svc_rollback",
                chargeLease.leaseOwner());

        assertFalse(finalized);
        assertEquals(PaymentAttemptStatus.CHARGE_SUCCEEDED, reload(fixture.attemptId()).getStatus());
        assertTrue(orderGroupEntityRepository.findAll().isEmpty());
        assertTrue(orderEntityRepository.findAll().isEmpty());
    }

    @Test
    void providerFinalizationIsIdempotentWithoutWorkerToken() {
        Fixture fixture = ambiguousFixture("svc-provider@migros.com");
        PaymentClaim chargeLease = reclaim(fixture);
        paymentAttemptService.recordChargeSuccess(
                fixture.attemptId(), chargeLease.leaseOwner(), "ch_svc_provider");

        assertTrue(paymentFinalizationService.finalizeProviderOrder(
                fixture.attemptId(), fixture.checkoutId(), "ch_svc_provider"));
        assertTrue(paymentFinalizationService.finalizeProviderOrder(
                fixture.attemptId(), fixture.checkoutId(), "ch_svc_provider"));

        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, reload(fixture.attemptId()).getStatus());
        assertEquals(1, orderGroupEntityRepository.findAll().size());
        assertEquals(1, orderEntityRepository.findAll().size());
    }

    @Test
    void repeatedStatusRecoveryCreatesAtMostOneOrder() {
        Fixture fixture = ambiguousFixture("svc-status@migros.com");
        PaymentClaim chargeLease = reclaim(fixture);
        paymentAttemptService.recordChargeSuccess(
                fixture.attemptId(), chargeLease.leaseOwner(), "ch_svc_status");

        userPaymentService.getPaymentStatus(fixture.token(), fixture.checkoutId());
        userPaymentService.getPaymentStatus(fixture.token(), fixture.checkoutId());

        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, reload(fixture.attemptId()).getStatus());
        assertEquals(1, orderGroupEntityRepository.findAll().size());
        assertEquals(1, orderEntityRepository.findAll().size());
    }

    @Test
    void unsafeThreeArgFinalizeOrderOverloadIsAbsent() throws Exception {
        for (java.lang.reflect.Method m : PaymentFinalizationService.class.getMethods()) {
            if (m.getName().equals("finalizeOrder") && m.getParameterCount() == 3) {
                throw new AssertionError(
                        "unsafe finalizeOrder(UUID,UUID,String) overload must be removed; "
                        + "use the 4-arg lease-fenced worker method or finalizeProviderOrder");
            }
        }
        java.lang.reflect.Method worker = PaymentFinalizationService.class.getMethod(
                "finalizeOrder", UUID.class, UUID.class, String.class, String.class);
        java.lang.reflect.Method provider = PaymentFinalizationService.class.getMethod(
                "finalizeProviderOrder", UUID.class, UUID.class, String.class);
        assertNotNull(worker);
        assertNotNull(provider);
    }

    @Test
    void legitimateFinalizationEntryPointsRemainTransactionalAndFunctional() throws Exception {
        java.lang.reflect.Method worker = PaymentFinalizationService.class.getMethod(
                "finalizeOrder", UUID.class, UUID.class, String.class, String.class);
        java.lang.reflect.Method provider = PaymentFinalizationService.class.getMethod(
                "finalizeProviderOrder", UUID.class, UUID.class, String.class);
        assertNotNull(worker.getAnnotation(
                org.springframework.transaction.annotation.Transactional.class),
                "4-arg worker finalizeOrder must remain @Transactional");
        assertNotNull(provider.getAnnotation(
                org.springframework.transaction.annotation.Transactional.class),
                "finalizeProviderOrder must remain @Transactional");

        Fixture workerFixture = ambiguousFixture("svc-legit-worker@migros.com");
        PaymentClaim workerLease = reclaim(workerFixture);
        paymentAttemptService.recordChargeSuccess(
                workerFixture.attemptId(), workerLease.leaseOwner(), "ch_svc_legit_worker");
        assertTrue(paymentFinalizationService.finalizeOrder(
                workerFixture.attemptId(), workerFixture.checkoutId(),
                "ch_svc_legit_worker", workerLease.leaseOwner()));
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED,
                reload(workerFixture.attemptId()).getStatus());

        Fixture providerFixture = ambiguousFixture("svc-legit-provider@migros.com");
        PaymentClaim providerLease = reclaim(providerFixture);
        paymentAttemptService.recordChargeSuccess(
                providerFixture.attemptId(), providerLease.leaseOwner(), "ch_svc_legit_provider");
        assertTrue(paymentFinalizationService.finalizeProviderOrder(
                providerFixture.attemptId(), providerFixture.checkoutId(), "ch_svc_legit_provider"));
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED,
                reload(providerFixture.attemptId()).getStatus());
    }

    @Test
    void alreadyManualReviewAttemptPreservesReasonAndCanonicalChargeOnConflictingSuccess() {
        Fixture fixture = ambiguousFixture("svc-manual-review@migros.com");
        PaymentClaim lease = reclaim(fixture);
        paymentAttemptService.recordChargeSuccess(
                fixture.attemptId(), lease.leaseOwner(), "ch_mr_canonical");
        paymentAttemptService.markManualReview(fixture.attemptId(), "original_review_reason");

        PaymentAttemptEntity before = reload(fixture.attemptId());
        assertEquals(PaymentAttemptStatus.MANUAL_REVIEW, before.getStatus());
        assertEquals("original_review_reason", before.getErrorCode());
        assertEquals("ch_mr_canonical", before.getStripeChargeId());
        String leaseBefore = before.getLeaseOwner();
        int stockBefore = stockOf(fixture.productId());
        String checkoutBefore = checkoutService.getCheckout(
                fixture.token(), fixture.checkoutId()).status();

        paymentAttemptService.recordProviderChargeSuccess(
                fixture.attemptId(), "ch_mr_conflict", 1000L, "try", fixture.checkoutId());

        PaymentAttemptEntity after = reload(fixture.attemptId());
        assertEquals(PaymentAttemptStatus.MANUAL_REVIEW, after.getStatus());
        assertEquals("original_review_reason", after.getErrorCode(),
                "existing MANUAL_REVIEW reason must never be overwritten");
        assertEquals("ch_mr_canonical", after.getStripeChargeId(),
                "canonical Stripe charge id must never be overwritten");
        assertEquals("conflict:ch_mr_conflict", after.getProviderStatus(),
                "conflicting charge evidence must be retained in sanitized evidence field");
        assertEquals(leaseBefore, after.getLeaseOwner(),
                "lease must remain unchanged for MANUAL_REVIEW");
        assertEquals(checkoutBefore,
                checkoutService.getCheckout(fixture.token(), fixture.checkoutId()).status());
        assertTrue(orderGroupEntityRepository.findAll().isEmpty(),
                "conflicting success on MANUAL_REVIEW must not create an order");
        assertTrue(orderEntityRepository.findAll().isEmpty());
        assertEquals(stockBefore, stockOf(fixture.productId()),
                "conflicting success on MANUAL_REVIEW must not mutate stock");
    }

    private Fixture ambiguousFixture(String mail) {
        try {
            ProductEntity product = createProduct("10.00", 5);
            UserEntity user = createUser(mail, product.getId());
            String token = token(user);
            UUID checkoutId = UUID.fromString(checkoutService.prepareCheckout(token).checkoutId());
            // doThrow style: re-stubbing must not invoke the mock, otherwise a
            // previously stubbed answer would trigger during stubbing.
            doThrow(new ApiConnectionException("timeout")).when(stripePaymentGateway)
                    .charge(anyString(), anyLong(), anyString(), anyString(), anyString());
            PaymentResponseDto ambiguous = userPaymentService.processCharge(checkoutId, "tok_visa", token);
            assertTrue(ambiguous.pending());
            PaymentAttemptEntity attempt =
                    paymentAttemptEntityRepository.findByCheckoutId(checkoutId).orElseThrow();
            return new Fixture(user, token, checkoutId, attempt.getId(),
                    attempt.getLeaseOwner(), product.getId());
        } catch (StripeException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Fixture successFixture(String mail, String chargeId) {
        try {
            ProductEntity product = createProduct("10.00", 5);
            UserEntity user = createUser(mail, product.getId());
            String token = token(user);
            UUID checkoutId = UUID.fromString(checkoutService.prepareCheckout(token).checkoutId());
            doAnswer(invocation -> paidCharge(chargeId,
                    invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(4)))
                    .when(stripePaymentGateway)
                    .charge(anyString(), anyLong(), anyString(), anyString(), anyString());
            PaymentResponseDto response = userPaymentService.processCharge(checkoutId, "tok_visa", token);
            assertTrue(response.success());
            PaymentAttemptEntity attempt =
                    paymentAttemptEntityRepository.findByCheckoutId(checkoutId).orElseThrow();
            return new Fixture(user, token, checkoutId, attempt.getId(),
                    attempt.getLeaseOwner(), product.getId());
        } catch (StripeException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private PaymentClaim reclaim(Fixture fixture) {
        expireLease(fixture.attemptId());
        return paymentAttemptService.claim(
                fixture.token(), fixture.checkoutId(),
                ChargeIdempotencyKeys.forCheckout(fixture.checkoutId()));
    }

    private void expireLease(UUID attemptId) {
        jdbcTemplate.update("UPDATE payment_attempt_entity SET lease_expires_at = now() - interval '1 minute' "
                + "WHERE attempt_id = ?", attemptId);
    }

    private String storedLease(UUID attemptId) {
        return jdbcTemplate.queryForObject(
                "SELECT lease_owner FROM payment_attempt_entity WHERE attempt_id = ?",
                String.class, attemptId);
    }

    private PaymentAttemptEntity reload(UUID attemptId) {
        return paymentAttemptEntityRepository.findById(attemptId).orElseThrow();
    }

    private int stockOf(Long productId) {
        return productEntityRepository.findById(productId).orElseThrow().getProductCount();
    }

    private Charge paidCharge(String id, long amountMinor, String currency, String checkoutId) {
        Charge charge = new Charge();
        charge.setId(id);
        charge.setPaid(true);
        charge.setStatus("succeeded");
        charge.setAmount(amountMinor);
        charge.setCurrency(currency);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("checkout_id", checkoutId);
        charge.setMetadata(metadata);
        return charge;
    }

    private String chargeSucceededPayload(String eventId, String chargeId, long amountMinor,
                                          String currency, UUID checkoutId) {
        return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"api_version\":\"2025-02-24.acacia\","
                + "\"type\":\"charge.succeeded\","
                + "\"data\":{\"object\":{\"id\":\"" + chargeId + "\",\"object\":\"charge\","
                + "\"amount\":" + amountMinor + ",\"currency\":\"" + currency + "\","
                + "\"paid\":true,\"status\":\"succeeded\","
                + "\"metadata\":{\"checkout_id\":\"" + checkoutId + "\"}}}}";
    }

    private Event deserializeEvent(String payload) {
        return StripeObject.deserializeStripeObject(
                payload, Event.class, ApiResource.getGlobalResponseGetter());
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
        product.setEffectivePrice(
                ProductPricingPolicy.effectivePrice(product.getProductPrice(), product.getProductDiscount()));
        product.setProductDescription("test product");
        return productEntityRepository.saveAndFlush(product);
    }

    private String token(UserEntity user) {
        return tokenService.generateUserToken(user.getUserMail());
    }
}
