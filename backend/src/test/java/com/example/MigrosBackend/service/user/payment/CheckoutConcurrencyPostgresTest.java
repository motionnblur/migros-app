package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.CheckoutResponseDto;
import com.example.MigrosBackend.dto.payment.CheckoutStatusDto;
import com.example.MigrosBackend.entity.checkout.CheckoutEntity;
import com.example.MigrosBackend.entity.checkout.CheckoutStatus;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.user.CheckoutStateException;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.CheckoutEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class CheckoutConcurrencyPostgresTest {

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
    }

    @Autowired
    private CheckoutService checkoutService;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private UserEntityRepository userEntityRepository;
    @Autowired
    private ProductEntityRepository productEntityRepository;
    @Autowired
    private CheckoutEntityRepository checkoutEntityRepository;
    @Autowired
    private OrderGroupEntityRepository orderGroupEntityRepository;
    @Autowired
    private OrderEntityRepository orderEntityRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE checkout_item_entity, checkout_entity, order_entity, "
                + "order_group_entity, product_entity, user_entity RESTART IDENTITY CASCADE");
    }

    @Test
    void competingPreparationsForLastUnitReserveExactlyOnceAndNeverGoNegative() throws Exception {
        ProductEntity product = createProduct("Last", "10.00", 1);
        UserEntity first = createUser("first@migros.com", product.getId());
        UserEntity second = createUser("second@migros.com", product.getId());
        String firstToken = token(first);
        String secondToken = token(second);

        List<Boolean> results = runConcurrently(
                () -> attemptPrepare(firstToken),
                () -> attemptPrepare(secondToken));

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count(),
                "exactly one competitor may reserve the last unit");

        ProductEntity reloaded = productEntityRepository.findById(product.getId()).orElseThrow();
        assertEquals(0, reloaded.getProductCount(), "stock must reach zero, never negative");

        List<CheckoutEntity> checkouts = checkoutEntityRepository.findAll();
        assertEquals(1, checkouts.size());
        assertEquals(CheckoutStatus.PREPARED, checkouts.get(0).getStatus());
    }

    @Test
    void concurrentOverlappingCartsCompleteWithoutDeadlockOrOverselling() throws Exception {
        ProductEntity a = createProduct("A", "10.00", 5);
        ProductEntity b = createProduct("B", "10.00", 5);
        ProductEntity c = createProduct("C", "10.00", 5);

        String tokenOne = token(createUser("one@migros.com", a.getId(), b.getId()));
        String tokenTwo = token(createUser("two@migros.com", b.getId(), c.getId()));
        String tokenThree = token(createUser("three@migros.com", a.getId(), c.getId()));

        List<Boolean> results = runConcurrently(
                () -> attemptPrepare(tokenOne),
                () -> attemptPrepare(tokenTwo),
                () -> attemptPrepare(tokenThree));

        assertTrue(results.stream().allMatch(Boolean::booleanValue),
                "overlapping carts must all succeed when stock is sufficient");
        assertEquals(3, productEntityRepository.findById(a.getId()).orElseThrow().getProductCount());
        assertEquals(3, productEntityRepository.findById(b.getId()).orElseThrow().getProductCount());
        assertEquals(3, productEntityRepository.findById(c.getId()).orElseThrow().getProductCount());
    }

    @Test
    void priceChangeAfterPreparationCannotChangeChargeOrOrder() {
        ProductEntity product = createProduct("Mutable", "10.00", 5);
        UserEntity user = createUser("mutable@migros.com", product.getId());
        String userToken = token(user);

        CheckoutResponseDto prepared = checkoutService.prepareCheckout(userToken);
        UUID checkoutId = UUID.fromString(prepared.checkoutId());

        ProductEntity mutated = productEntityRepository.findById(product.getId()).orElseThrow();
        mutated.setProductPrice(new BigDecimal("999.00"));
        productEntityRepository.saveAndFlush(mutated);

        checkoutService.beginPayment(userToken, checkoutId);
        CheckoutStatusDto consumed = checkoutService.completePayment(checkoutId, "ch_snapshot");

        assertEquals("CONSUMED", consumed.status());
        assertEquals(0, new BigDecimal("10.00").compareTo(consumed.totalAmount()));
        assertEquals(1000L, consumed.amountMinor());

        List<OrderEntity> orders = orderEntityRepository.findAll().stream()
                .filter(order -> order.getItemId().equals(product.getId()))
                .toList();
        assertEquals(1, orders.size());
        assertEquals(0, new BigDecimal("10.00").compareTo(orders.get(0).getPrice()));
        assertEquals(0, new BigDecimal("10.00").compareTo(orders.get(0).getTotalPrice()));

        assertEquals(4, productEntityRepository.findById(product.getId()).orElseThrow().getProductCount(),
                "finalization must not decrement reserved stock a second time");
    }

    @Test
    void cartMutationAfterPreparationCannotAddItemsToTheOrder() {
        ProductEntity reserved = createProduct("Reserved", "10.00", 5);
        ProductEntity sneaked = createProduct("Sneaked", "1.00", 5);
        UserEntity user = createUser("cart@migros.com", reserved.getId());
        String userToken = token(user);

        CheckoutResponseDto prepared = checkoutService.prepareCheckout(userToken);
        UUID checkoutId = UUID.fromString(prepared.checkoutId());

        UserEntity reloaded = userEntityRepository.findById(user.getId()).orElseThrow();
        reloaded.setProductsIdsInCart(new ArrayList<>(List.of(sneaked.getId())));
        userEntityRepository.saveAndFlush(reloaded);

        checkoutService.beginPayment(userToken, checkoutId);
        checkoutService.completePayment(checkoutId, "ch_cart");

        List<OrderEntity> orders = orderEntityRepository.findAll();
        assertEquals(1, orders.size());
        assertEquals(reserved.getId(), orders.get(0).getItemId());
        assertEquals(0, new BigDecimal("10.00").compareTo(orders.get(0).getPrice()));
    }

    @Test
    void concurrentCancellationReleasesStockExactlyOnce() throws Exception {
        ProductEntity product = createProduct("Cancel", "10.00", 5);
        UserEntity user = createUser("cancel@migros.com", product.getId());
        String userToken = token(user);

        CheckoutResponseDto prepared = checkoutService.prepareCheckout(userToken);
        UUID checkoutId = UUID.fromString(prepared.checkoutId());
        assertEquals(4, productEntityRepository.findById(product.getId()).orElseThrow().getProductCount());

        List<Boolean> results = runConcurrently(
                () -> attemptCancel(userToken, checkoutId),
                () -> attemptCancel(userToken, checkoutId));

        assertTrue(results.stream().allMatch(Boolean::booleanValue), "repeated cancellation must be idempotent");
        assertEquals(5, productEntityRepository.findById(product.getId()).orElseThrow().getProductCount(),
                "stock must be returned exactly once");
        assertEquals(CheckoutStatus.CANCELLED,
                checkoutEntityRepository.findById(checkoutId).orElseThrow().getStatus());
    }

    @Test
    void paidCheckoutCannotBeCancelledAndNeverRestoresStock() {
        ProductEntity product = createProduct("Paid", "10.00", 5);
        UserEntity user = createUser("paid@migros.com", product.getId());
        String userToken = token(user);

        CheckoutResponseDto prepared = checkoutService.prepareCheckout(userToken);
        UUID checkoutId = UUID.fromString(prepared.checkoutId());
        checkoutService.beginPayment(userToken, checkoutId);
        checkoutService.completePayment(checkoutId, "ch_paid");

        assertThrows(CheckoutStateException.class, () -> checkoutService.cancelCheckout(userToken, checkoutId));
        assertEquals(4, productEntityRepository.findById(product.getId()).orElseThrow().getProductCount());
    }

    @Test
    void concurrentFinalizationCreatesAtMostOneOrderGroup() throws Exception {
        ProductEntity product = createProduct("Once", "10.00", 5);
        UserEntity user = createUser("once@migros.com", product.getId());
        String userToken = token(user);

        CheckoutResponseDto prepared = checkoutService.prepareCheckout(userToken);
        UUID checkoutId = UUID.fromString(prepared.checkoutId());
        checkoutService.beginPayment(userToken, checkoutId);

        List<Boolean> results = runConcurrently(
                () -> attemptComplete(checkoutId, "ch_once"),
                () -> attemptComplete(checkoutId, "ch_once"));

        assertTrue(results.stream().allMatch(Boolean::booleanValue));

        List<OrderGroupEntity> groups = orderGroupEntityRepository.findByUserId(user.getId());
        assertEquals(1, groups.size(), "one checkout must produce at most one order group");
        assertEquals(1, orderEntityRepository.findByOrderGroup_Id(groups.get(0).getId()).size());

        // A later idempotent retry still resolves to the same order and creates nothing new.
        checkoutService.createOrderFromCheckout(checkoutId);
        assertEquals(1, orderGroupEntityRepository.findByUserId(user.getId()).size());
    }

    private boolean attemptPrepare(String userToken) {
        try {
            checkoutService.prepareCheckout(userToken);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean attemptCancel(String userToken, UUID checkoutId) {
        try {
            checkoutService.cancelCheckout(userToken, checkoutId);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean attemptComplete(UUID checkoutId, String chargeId) {
        try {
            checkoutService.completePayment(checkoutId, chargeId);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @SafeVarargs
    private final List<Boolean> runConcurrently(Callable<Boolean>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (Callable<Boolean> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<Boolean> results = new ArrayList<>();
        for (Future<Boolean> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }

    private UserEntity createUser(String mail, Long... productIds) {
        UserEntity user = new UserEntity();
        user.setUserMail(mail);
        user.setBanned(false);
        user.setProductsIdsInCart(new ArrayList<>(Arrays.asList(productIds)));
        return userEntityRepository.saveAndFlush(user);
    }

    private ProductEntity createProduct(String name, String price, int stock) {
        ProductEntity product = new ProductEntity();
        product.setProductName(name);
        product.setSubcategoryName("general");
        product.setProductCount(stock);
        product.setProductPrice(new BigDecimal(price));
        product.setProductDiscount(BigDecimal.ZERO);
        product.setProductDescription("test product");
        return productEntityRepository.saveAndFlush(product);
    }

    private String token(UserEntity user) {
        return tokenService.generateUserToken(user.getUserMail());
    }
}
