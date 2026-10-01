package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.CheckoutResponseDto;
import com.example.MigrosBackend.dto.payment.CheckoutStatusDto;
import com.example.MigrosBackend.dto.payment.PaymentResponseDto;
import com.example.MigrosBackend.entity.payment.PaymentAttemptEntity;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.user.CheckoutStateException;
import com.example.MigrosBackend.helper.ProductPricingPolicy;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.PaymentAttemptEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class PaymentIdempotencyPostgresTest {

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

    @SpyBean
    private CheckoutService checkoutServiceSpy;

    @BeforeEach
    void setUp() throws StripeException {
        jdbcTemplate.execute("TRUNCATE TABLE stripe_event_entity, payment_attempt_entity, "
                + "checkout_item_entity, checkout_entity, order_entity, order_group_entity, "
                + "product_entity, user_entity RESTART IDENTITY CASCADE");
        when(stripePaymentGateway.findChargeForCheckout(anyString())).thenReturn(Optional.empty());
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

    private void stubCharge(String id) throws StripeException {
        when(stripePaymentGateway.charge(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> paidCharge(
                        id,
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(4)));
    }

    @Test
    void firstChargeUsesExactSnapshotAmountCurrencyAndStableIdempotencyKey() throws Exception {
        ProductEntity product = createProduct("10.00", 5);
        UserEntity user = createUser("buyer@migros.com", product.getId());
        String token = token(user);
        CheckoutResponseDto checkout = checkoutService.prepareCheckout(token);
        UUID checkoutId = UUID.fromString(checkout.checkoutId());
        stubCharge("ch_first");

        PaymentResponseDto response = userPaymentService.processCharge(checkoutId, "tok_visa", token);

        assertTrue(response.success());
        assertFalse(response.pending());
        assertEquals("ORDER_FINALIZED", response.state());

        ArgumentCaptor<Long> amount = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> currency = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(stripePaymentGateway).charge(eq("tok_visa"), amount.capture(), currency.capture(),
                key.capture(), eq(checkoutId.toString()));
        assertEquals(checkout.amountMinor(), amount.getValue());
        assertEquals("try", currency.getValue());
        assertEquals("checkout:" + checkoutId + ":charge-v1", key.getValue());
    }

    @Test
    void repeatingCompletedRequestReturnsStoredResultWithoutSecondCharge() throws Exception {
        ProductEntity product = createProduct("10.00", 5);
        UserEntity user = createUser("buyer@migros.com", product.getId());
        String token = token(user);
        UUID checkoutId = UUID.fromString(checkoutService.prepareCheckout(token).checkoutId());
        stubCharge("ch_once");

        PaymentResponseDto first = userPaymentService.processCharge(checkoutId, "tok_visa", token);
        PaymentResponseDto second = userPaymentService.processCharge(checkoutId, "tok_visa", token);

        assertTrue(first.success());
        assertTrue(second.success());
        assertEquals(first.chargeId(), second.chargeId());
        verify(stripePaymentGateway, times(1))
                .charge(anyString(), anyLong(), anyString(), anyString(), anyString());
        assertEquals(1, orderGroupEntityRepository.findAll().size());
    }

    @Test
    void twoSimultaneousRequestsPermitOnlyOneWorkerAndOneEconomicCharge() throws Exception {
        ProductEntity product = createProduct("10.00", 5);
        UserEntity user = createUser("buyer@migros.com", product.getId());
        String token = token(user);
        UUID checkoutId = UUID.fromString(checkoutService.prepareCheckout(token).checkoutId());

        CountDownLatch enteredProvider = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(stripePaymentGateway.charge(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    enteredProvider.countDown();
                    releaseProvider.await(30, TimeUnit.SECONDS);
                    return paidCharge("ch_concurrent", invocation.getArgument(1),
                            invocation.getArgument(2), invocation.getArgument(4));
                });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<PaymentResponseDto> worker = pool.submit(
                    () -> userPaymentService.processCharge(checkoutId, "tok_visa", token));
            assertTrue(enteredProvider.await(30, TimeUnit.SECONDS));
            Future<PaymentResponseDto> duplicate = pool.submit(
                    () -> userPaymentService.processCharge(checkoutId, "tok_visa", token));

            PaymentResponseDto duplicateResponse = duplicate.get(30, TimeUnit.SECONDS);
            assertTrue(duplicateResponse.pending());
            verify(stripePaymentGateway, times(1))
                    .charge(anyString(), anyLong(), anyString(), anyString(), anyString());

            releaseProvider.countDown();
            PaymentResponseDto workerResponse = worker.get(30, TimeUnit.SECONDS);
            assertTrue(workerResponse.success());
        } finally {
            releaseProvider.countDown();
            pool.shutdownNow();
        }

        assertEquals(1, orderGroupEntityRepository.findAll().size());
        assertEquals(1, orderEntityRepository.findAll().size());
    }

    @Test
    void retryAfterAmbiguousTimeoutReusesIdenticalIdempotencyKeyAmountAndCurrency() throws Exception {
        ProductEntity product = createProduct("10.00", 5);
        UserEntity user = createUser("buyer@migros.com", product.getId());
        String token = token(user);
        UUID checkoutId = UUID.fromString(checkoutService.prepareCheckout(token).checkoutId());

        when(stripePaymentGateway.charge(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new ApiConnectionException("timeout"))
                .thenAnswer(invocation -> paidCharge("ch_retry", invocation.getArgument(1),
                        invocation.getArgument(2), invocation.getArgument(4)));

        PaymentResponseDto ambiguous = userPaymentService.processCharge(checkoutId, "tok_visa", token);
        assertFalse(ambiguous.success());
        assertTrue(ambiguous.pending());

        // Simulate the worker's lease expiring so a retry can reclaim it.
        jdbcTemplate.update("UPDATE payment_attempt_entity SET lease_expires_at = now() - interval '1 minute'");

        PaymentResponseDto retried = userPaymentService.processCharge(checkoutId, "tok_visa", token);

        assertTrue(retried.success());
        ArgumentCaptor<Long> amount = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> currency = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(stripePaymentGateway, times(2)).charge(anyString(), amount.capture(), currency.capture(),
                key.capture(), anyString());
        assertEquals(2, amount.getAllValues().size());
        assertEquals(amount.getAllValues().get(0), amount.getAllValues().get(1));
        assertEquals(currency.getAllValues().get(0), currency.getAllValues().get(1));
        assertEquals(key.getAllValues().get(0), key.getAllValues().get(1));
        assertEquals("checkout:" + checkoutId + ":charge-v1", key.getAllValues().get(0));
    }

    @Test
    void crashAfterProviderSuccessRecoversSameChargeWithoutAnotherEconomicCharge() throws Exception {
        ProductEntity product = createProduct("10.00", 5);
        UserEntity user = createUser("buyer@migros.com", product.getId());
        String token = token(user);
        UUID checkoutId = UUID.fromString(checkoutService.prepareCheckout(token).checkoutId());

        // Simulate a crash after Stripe accepted but before local persistence:
        // the attempt is left PROCESSING with an expired lease and no charge id.
        PaymentResponseDto firstAttempt = attemptWithAmbiguousProviderFailure(checkoutId, token);
        assertTrue(firstAttempt.pending());

        UUID attemptId = paymentAttemptEntityRepository.findByCheckoutId(checkoutId).orElseThrow().getId();
        jdbcTemplate.update("UPDATE payment_attempt_entity SET status = 'PROCESSING', "
                + "stripe_charge_id = NULL, lease_expires_at = now() - interval '1 minute' WHERE attempt_id = ?",
                attemptId);

        Charge recovered = paidCharge("ch_recovered", 1000L, "try", checkoutId.toString());
        clearInvocations(stripePaymentGateway);
        when(stripePaymentGateway.findChargeForCheckout(checkoutId.toString())).thenReturn(Optional.of(recovered));

        paymentRecoveryService.recover(attemptId);

        PaymentAttemptEntity attempt = paymentAttemptEntityRepository.findById(attemptId).orElseThrow();
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, attempt.getStatus());
        assertEquals("ch_recovered", attempt.getStripeChargeId());
        verify(stripePaymentGateway, never()).charge(anyString(), anyLong(), anyString(), anyString(), anyString());
        assertEquals(1, orderGroupEntityRepository.findAll().size());
    }

    @Test
    void orderTransactionFailureAfterChargeLeavesRecoverableStateAndRetryCreatesOneOrder() throws Exception {
        ProductEntity product = createProduct("10.00", 5);
        UserEntity user = createUser("buyer@migros.com", product.getId());
        String token = token(user);
        UUID checkoutId = UUID.fromString(checkoutService.prepareCheckout(token).checkoutId());
        stubCharge("ch_captured");

        doThrow(new RuntimeException("simulated finalization failure"))
                .doCallRealMethod()
                .when(checkoutServiceSpy).completePayment(eq(checkoutId), eq("ch_captured"));

        PaymentResponseDto response = userPaymentService.processCharge(checkoutId, "tok_visa", token);

        assertTrue(response.success());
        assertTrue(response.pending());
        assertEquals("CHARGE_SUCCEEDED", response.state());
        PaymentAttemptEntity stuck = paymentAttemptEntityRepository.findByCheckoutId(checkoutId).orElseThrow();
        assertEquals(PaymentAttemptStatus.CHARGE_SUCCEEDED, stuck.getStatus());

        // Retry through status access finalizes exactly one order and never re-charges.
        userPaymentService.getPaymentStatus(token, checkoutId);

        verify(stripePaymentGateway, times(1))
                .charge(anyString(), anyLong(), anyString(), anyString(), anyString());
        assertEquals(1, orderGroupEntityRepository.findAll().size());
        assertEquals(1, orderEntityRepository.findAll().size());
    }

    @Test
    void providerDeclineRecordsTerminalNoChargeResultAndNeverCreatesAnOrder() throws Exception {
        ProductEntity product = createProduct("10.00", 5);
        UserEntity user = createUser("buyer@migros.com", product.getId());
        String token = token(user);
        UUID checkoutId = UUID.fromString(checkoutService.prepareCheckout(token).checkoutId());

        CardException decline = org.mockito.Mockito.mock(CardException.class);
        when(decline.getCode()).thenReturn("card_declined");
        when(stripePaymentGateway.charge(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(decline);

        PaymentResponseDto response = userPaymentService.processCharge(checkoutId, "tok_visa", token);

        assertFalse(response.success());
        assertFalse(response.pending());
        assertEquals("FAILED_FINAL", response.state());
        PaymentAttemptEntity attempt = paymentAttemptEntityRepository.findByCheckoutId(checkoutId).orElseThrow();
        assertEquals(PaymentAttemptStatus.FAILED_FINAL, attempt.getStatus());
        assertEquals(0, orderGroupEntityRepository.findAll().size());
    }

    @Test
    void anotherUserCannotReadOrRetryTheAttempt() throws Exception {
        ProductEntity product = createProduct("10.00", 5);
        UserEntity owner = createUser("owner@migros.com", product.getId());
        String ownerToken = token(owner);
        UUID checkoutId = UUID.fromString(checkoutService.prepareCheckout(ownerToken).checkoutId());

        UserEntity intruder = createUser("intruder@migros.com");
        String intruderToken = token(intruder);

        assertThrows(RuntimeException.class,
                () -> userPaymentService.processCharge(checkoutId, "tok_visa", intruderToken));
        assertThrows(RuntimeException.class,
                () -> userPaymentService.getPaymentStatus(intruderToken, checkoutId));
        verify(stripePaymentGateway, never())
                .charge(anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void repeatedFinalizationReturnsExistingOrderBecauseCheckoutToOrderIsUnique() {
        ProductEntity product = createProduct("10.00", 5);
        UserEntity user = createUser("buyer@migros.com", product.getId());
        String token = token(user);
        UUID checkoutId = UUID.fromString(checkoutService.prepareCheckout(token).checkoutId());

        Charge charge = paidCharge("ch_repeat", 1000L, "try", checkoutId.toString());
        assertNotNull(charge);

        checkoutService.beginPayment(token, checkoutId);
        checkoutService.completePayment(checkoutId, "ch_repeat");
        checkoutService.completePayment(checkoutId, "ch_repeat");
        checkoutService.createOrderFromCheckout(checkoutId);

        List<OrderGroupEntity> groups = orderGroupEntityRepository.findAll();
        assertEquals(1, groups.size());
        assertEquals(1, orderEntityRepository.findAll().size());
    }

    @Test
    void concurrentCancelDuringInFlightChargeCannotPreventOrderFinalization() throws Exception {
        ProductEntity product = createProduct("10.00", 5);
        UserEntity user = createUser("buyer@migros.com", product.getId());
        String token = token(user);
        UUID checkoutId = UUID.fromString(checkoutService.prepareCheckout(token).checkoutId());
        assertEquals(4, productEntityRepository.findById(product.getId()).orElseThrow().getProductCount());

        CountDownLatch enteredProvider = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(stripePaymentGateway.charge(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    enteredProvider.countDown();
                    assertTrue(releaseProvider.await(30, TimeUnit.SECONDS));
                    return paidCharge("ch_race_cancel", invocation.getArgument(1),
                            invocation.getArgument(2), invocation.getArgument(4));
                });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<PaymentResponseDto> chargeFuture = pool.submit(
                    () -> userPaymentService.processCharge(checkoutId, "tok_visa", token));
            assertTrue(enteredProvider.await(30, TimeUnit.SECONDS));

            // The checkout is now PAYMENT_PROCESSING with a charge in flight.
            // A concurrent user cancellation must be rejected without touching stock.
            Future<?> cancelFuture = pool.submit(() -> {
                try {
                    checkoutService.cancelCheckout(token, checkoutId);
                    throw new AssertionError("cancel of a processing checkout must be rejected");
                } catch (CheckoutStateException expected) {
                    // Expected: reconciliation stays pending, reservation is kept.
                }
                return null;
            });
            cancelFuture.get(30, TimeUnit.SECONDS);

            releaseProvider.countDown();
            PaymentResponseDto chargeResponse = chargeFuture.get(30, TimeUnit.SECONDS);
            assertTrue(chargeResponse.success());
        } finally {
            releaseProvider.countDown();
            pool.shutdownNow();
        }

        verify(stripePaymentGateway, times(1))
                .charge(anyString(), anyLong(), anyString(), anyString(), anyString());
        assertEquals(1, orderGroupEntityRepository.findAll().size(),
                "exactly one order group must be finalized");
        assertEquals(1, orderEntityRepository.findAll().size());
        assertEquals(4, productEntityRepository.findById(product.getId()).orElseThrow().getProductCount(),
                "stock must stay reserved; the concurrent cancel must not restore it");
        CheckoutStatusDto status = checkoutService.getCheckout(token, checkoutId);
        assertEquals("CONSUMED", status.status());
    }

    @Test
    void cancellingProcessingCheckoutDirectlyIsRejectedAndKeepsReservation() {
        ProductEntity product = createProduct("10.00", 5);
        UserEntity user = createUser("buyer@migros.com", product.getId());
        String token = token(user);
        UUID checkoutId = UUID.fromString(checkoutService.prepareCheckout(token).checkoutId());
        checkoutService.beginPayment(token, checkoutId);

        assertThrows(CheckoutStateException.class,
                () -> checkoutService.cancelCheckout(token, checkoutId));
        assertEquals(4, productEntityRepository.findById(product.getId()).orElseThrow().getProductCount());
        assertEquals("PAYMENT_PROCESSING",
                checkoutService.getCheckout(token, checkoutId).status());
    }

    @Test
    void providerDeclineReleasesStockOnceAndCancelsProcessingCheckout() throws Exception {
        ProductEntity product = createProduct("10.00", 5);
        UserEntity user = createUser("buyer@migros.com", product.getId());
        String token = token(user);
        UUID checkoutId = UUID.fromString(checkoutService.prepareCheckout(token).checkoutId());

        CardException decline = org.mockito.Mockito.mock(CardException.class);
        when(decline.getCode()).thenReturn("card_declined");
        when(stripePaymentGateway.charge(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(decline);

        PaymentResponseDto response = userPaymentService.processCharge(checkoutId, "tok_visa", token);

        assertFalse(response.success());
        assertFalse(response.pending());
        assertEquals("FAILED_FINAL", response.state());
        assertEquals(5, productEntityRepository.findById(product.getId()).orElseThrow().getProductCount(),
                "a provider-confirmed decline must release the reservation exactly once");
        assertEquals("CANCELLED", checkoutService.getCheckout(token, checkoutId).status());
    }

    @Test
    void ambiguousProviderFailureKeepsReservationAndProcessingState() throws Exception {
        ProductEntity product = createProduct("10.00", 5);
        UserEntity user = createUser("buyer@migros.com", product.getId());
        String token = token(user);
        UUID checkoutId = UUID.fromString(checkoutService.prepareCheckout(token).checkoutId());

        when(stripePaymentGateway.charge(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new ApiConnectionException("timeout"));

        PaymentResponseDto response = userPaymentService.processCharge(checkoutId, "tok_visa", token);

        assertFalse(response.success());
        assertTrue(response.pending());
        assertEquals(4, productEntityRepository.findById(product.getId()).orElseThrow().getProductCount(),
                "an ambiguous outcome is not proof of no-charge and must keep the reservation");
        assertEquals("PAYMENT_PROCESSING",
                checkoutService.getCheckout(token, checkoutId).status());
    }

    @Test
    void staleLeaseAloneCannotReleaseStock() throws Exception {
        ProductEntity product = createProduct("10.00", 5);
        UserEntity user = createUser("buyer@migros.com", product.getId());
        String token = token(user);
        UUID checkoutId = UUID.fromString(checkoutService.prepareCheckout(token).checkoutId());

        // Create a PROCESSING attempt with an ambiguous provider outcome.
        when(stripePaymentGateway.charge(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new ApiConnectionException("timeout"));
        PaymentResponseDto ambiguous = userPaymentService.processCharge(checkoutId, "tok_visa", token);
        assertTrue(ambiguous.pending());
        UUID attemptId = paymentAttemptEntityRepository.findByCheckoutId(checkoutId).orElseThrow().getId();

        jdbcTemplate.update("UPDATE payment_attempt_entity SET lease_expires_at = now() - interval '1 minute' "
                + "WHERE attempt_id = ?", attemptId);

        // No reconciliation trigger runs here: the checkout must stay processing
        // with its reservation intact until authoritative provider evidence arrives.
        assertEquals("PAYMENT_PROCESSING",
                checkoutService.getCheckout(token, checkoutId).status());
        assertEquals(4, productEntityRepository.findById(product.getId()).orElseThrow().getProductCount());
    }

    private PaymentResponseDto attemptWithAmbiguousProviderFailure(UUID checkoutId, String token)
            throws StripeException {
        when(stripePaymentGateway.charge(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new ApiConnectionException("timeout"));
        return userPaymentService.processCharge(checkoutId, "tok_visa", token);
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
