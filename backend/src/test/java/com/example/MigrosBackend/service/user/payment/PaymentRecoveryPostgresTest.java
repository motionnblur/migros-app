package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.entity.checkout.CheckoutEntity;
import com.example.MigrosBackend.entity.payment.PaymentAttemptEntity;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.user.PaymentStateException;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.CheckoutEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.PaymentAttemptEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class PaymentRecoveryPostgresTest {

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
    private PaymentAttemptService paymentAttemptService;
    @Autowired
    private PaymentRecoveryService paymentRecoveryService;
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

    @MockBean
    private StripePaymentGateway stripePaymentGateway;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE TABLE stripe_event_entity, payment_attempt_entity, "
                + "checkout_item_entity, checkout_entity, order_entity, order_group_entity, "
                + "product_entity, user_entity RESTART IDENTITY CASCADE");
    }

    @Test
    void refundIsIdempotentAndPersisted() throws StripeException {
        PaymentAttemptEntity attempt = persistFinalizedAttempt("ch_refund");
        Refund refund = new Refund();
        refund.setId("re_1");
        when(stripePaymentGateway.refund(eq("ch_refund"), anyString())).thenReturn(refund);

        assertTrue(paymentRecoveryService.refund(attempt.getId(), "ch_refund", "test"));
        assertTrue(paymentRecoveryService.refund(attempt.getId(), "ch_refund", "test"));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(stripePaymentGateway, times(1)).refund(eq("ch_refund"), key.capture());
        assertEquals("attempt:" + attempt.getId() + ":refund-v1", key.getValue());

        PaymentAttemptEntity reloaded = paymentAttemptEntityRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(PaymentAttemptStatus.REFUNDED, reloaded.getStatus());
        assertEquals("re_1", reloaded.getRefundId());
    }

    @Test
    void recoveryFinalizesAChargeSucceededAttemptExactlyOnce() {
        ProductEntity product = createProduct("10.00", 5);
        UserEntity user = createUser("buyer@migros.com", product.getId());
        String token = tokenService.generateUserToken(user.getUserMail());
        UUID checkoutId = UUID.fromString(checkoutService.prepareCheckout(token).checkoutId());
        checkoutService.beginPayment(token, checkoutId);
        PaymentAttemptEntity attempt = persistAttempt(checkoutId, PaymentAttemptStatus.CHARGE_SUCCEEDED, "ch_ok");

        paymentRecoveryService.recover(attempt.getId());
        paymentRecoveryService.recover(attempt.getId());

        PaymentAttemptEntity reloaded = paymentAttemptEntityRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(PaymentAttemptStatus.ORDER_FINALIZED, reloaded.getStatus());
        assertEquals(1, orderGroupEntityRepository.findAll().size());
        assertEquals(1, orderEntityRepository.findAll().size());
    }

    @Test
    void outOfOrderChargeEventCannotRegressARefundedAttempt() {
        PaymentAttemptEntity attempt = persistFinalizedAttempt("ch_regress");
        paymentAttemptService.recordRefunded(attempt.getId(), "re_regress");

        // Historical success for the same charge is an idempotent preserve of
        // the refund state: no throw, no regression, no new fulfillment.
        paymentAttemptService.recordProviderChargeSuccess(
                attempt.getId(), "ch_regress", attempt.getAmountMinor(),
                attempt.getCurrency(), attempt.getCheckoutId());
        assertThrows(PaymentStateException.class,
                () -> paymentAttemptService.recordDecline(attempt.getId(), null, "card_declined"));

        PaymentAttemptEntity reloaded = paymentAttemptEntityRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(PaymentAttemptStatus.REFUNDED, reloaded.getStatus());
        assertEquals("ch_regress", reloaded.getStripeChargeId());
        assertEquals("re_regress", reloaded.getRefundId());
    }

    private PaymentAttemptEntity persistFinalizedAttempt(String chargeId) {
        ProductEntity product = createProduct("10.00", 5);
        UserEntity user = createUser("frozen@migros.com", product.getId());
        String token = tokenService.generateUserToken(user.getUserMail());
        UUID checkoutId = UUID.fromString(checkoutService.prepareCheckout(token).checkoutId());
        checkoutService.beginPayment(token, checkoutId);
        return persistAttempt(checkoutId, PaymentAttemptStatus.ORDER_FINALIZED, chargeId);
    }

    private PaymentAttemptEntity persistAttempt(UUID checkoutId, PaymentAttemptStatus status, String chargeId) {
        CheckoutEntity checkout = checkoutEntityRepository.findById(checkoutId).orElseThrow();
        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setId(UUID.randomUUID());
        attempt.setCheckout(checkout);
        attempt.setIdempotencyKey("checkout:" + checkoutId + ":charge-v1");
        attempt.setAmountMinor(checkout.getAmountMinor());
        attempt.setCurrency(checkout.getCurrency());
        attempt.setStatus(status);
        attempt.setStripeChargeId(chargeId);
        attempt.setCreatedAt(LocalDateTime.now());
        attempt.setUpdatedAt(LocalDateTime.now());
        return paymentAttemptEntityRepository.saveAndFlush(attempt);
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
