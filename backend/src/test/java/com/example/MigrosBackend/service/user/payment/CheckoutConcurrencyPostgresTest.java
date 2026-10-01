package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.CheckoutResponseDto;
import com.example.MigrosBackend.dto.payment.CheckoutStatusDto;
import com.example.MigrosBackend.entity.checkout.CheckoutEntity;
import com.example.MigrosBackend.entity.checkout.CheckoutStatus;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.ProductEditConflictException;
import com.example.MigrosBackend.exception.user.CheckoutStateException;
import com.example.MigrosBackend.helper.ProductPricingPolicy;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.CheckoutEntityRepository;
import com.example.MigrosBackend.repository.user.CheckoutItemEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.admin.supply.AdminSupplyService;
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
    private com.example.MigrosBackend.service.user.supply.UserCartService userCartService;
    @Autowired
    private AdminSupplyService adminSupplyService;
    @Autowired
    private AdminEntityRepository adminEntityRepository;
    @Autowired
    private CategoryEntityRepository categoryEntityRepository;
    @Autowired
    private UserEntityRepository userEntityRepository;
    @Autowired
    private ProductEntityRepository productEntityRepository;
    @Autowired
    private CheckoutEntityRepository checkoutEntityRepository;
    @Autowired
    private CheckoutItemEntityRepository checkoutItemEntityRepository;
    @Autowired
    private OrderGroupEntityRepository orderGroupEntityRepository;
    @Autowired
    private OrderEntityRepository orderEntityRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE checkout_item_entity, checkout_entity, order_entity, "
                + "order_group_entity, product_entity, product_image_entity, category_entity, "
                + "admin_entity, user_entity RESTART IDENTITY CASCADE");
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

    /**
     * The reservation and a cart addition are both read-modify-write on the same
     * user row, so they have to serialize on that row's write lock.
     *
     * <p>Note what is <em>not</em> asserted: that only one of the two may
     * succeed. Both succeeding is a legitimate serial order - the preparation
     * reserves the cart it found and empties it, then the addition adds to the
     * now-empty cart - and rejecting either outcome would be inventing an
     * exclusion the domain does not have. What must hold is that the
     * interleaving is indistinguishable from <em>some</em> serial order: exactly
     * one reservation exists, the reserved quantity is exactly the cart the
     * preparation observed, and that quantity is deducted from stock exactly
     * once with no unit lost or double-counted.
     */
    @Test
    void preparationRacingACartAdditionProducesExactlyOneReservation() throws Exception {
        ProductEntity product = createProduct("Raced", "10.00", 5);
        UserEntity user = createUser("prepare-add@migros.com", product.getId());
        String userToken = token(user);

        List<Boolean> results = runConcurrently(
                () -> attemptPrepare(userToken),
                () -> attemptAdd(product.getId(), user.getUserMail()));

        // The add only fails if the preparation took the last unit, which needs
        // five cart entries and this race can produce at most two.
        assertTrue(results.get(1), "adding to a cart that holds at most two units of five "
                + "stock must succeed regardless of the interleaving");

        assertEquals(1, checkoutEntityRepository.findAll().size(), "one reservation at most");

        // The preparation observed either the one pre-seeded unit (addition landed
        // after it emptied the cart) or two (addition landed first). Both are
        // valid serial orders; any other number means an item was lost or a
        // unit was counted twice.
        int reserved = checkoutItemEntityRepository
                .findByCheckout_IdOrderByProductIdAsc(checkoutEntityRepository.findAll().get(0).getId())
                .stream()
                .mapToInt(item -> item.getQuantity())
                .sum();
        assertTrue(reserved == 1 || reserved == 2,
                "the reservation must cover exactly the cart the preparation observed, "
                        + "but reserved " + reserved + " units");

        assertEquals(5 - reserved,
                productEntityRepository.findById(product.getId()).orElseThrow().getProductCount(),
                "stock must be deducted exactly once per reserved unit");
    }

    private boolean attemptAdd(Long productId, String userMail) {
        try {
            userCartService.addProductToCart(productId, userMail);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
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
        product.setEffectivePrice(
                ProductPricingPolicy.effectivePrice(product.getProductPrice(), product.getProductDiscount()));
        product.setProductDescription("test product");
        return productEntityRepository.saveAndFlush(product);
    }

    private String token(UserEntity user) {
        return tokenService.generateUserToken(user.getUserMail());
    }

    // -------------------------------------------------------------------------
    // Product edit version vs. stock reservation
    //
    // A checkout decrement is a stock writer, so it has to advance the product's
    // edit version too. If it did not, an admin form loaded before the reservation
    // would still consider the product unchanged and would be allowed to write
    // back the pre-reservation count - handing out stock that is already sold.
    // -------------------------------------------------------------------------

    @Test
    void reservingStockAdvancesTheProductEditVersion() {
        ProductEntity product = createProduct("Versioned", "10.00", 5);
        UserEntity user = createUser("versioned@migros.com", product.getId());

        long before = jdbcTemplate.queryForObject(
                "SELECT version FROM product_entity WHERE product_entity_id = ?", Long.class,
                product.getId());

        checkoutService.prepareCheckout(token(user));

        long after = jdbcTemplate.queryForObject(
                "SELECT version FROM product_entity WHERE product_entity_id = ?", Long.class,
                product.getId());
        assertEquals(1L, after - before,
                "a JPA-managed decrement must advance the version like any other product write");
        assertEquals(4, productEntityRepository.findById(product.getId()).orElseThrow().getProductCount());
    }

    @Test
    void aStaleEditCannotReplaceStockThatACheckoutAlreadyReserved() {
        ProductEntity product = createProduct("Reserved", "10.00", 10);
        UserEntity user = createUser("stale@migros.com", product.getId());
        String userToken = token(user);

        long versionSeenByTheEditor = jdbcTemplate.queryForObject(
                "SELECT version FROM product_entity WHERE product_entity_id = ?", Long.class,
                product.getId());
        AdminEntity admin = createAdmin("stale-editor");
        CategoryEntity category = createCategory(31);

        // The editor loaded the form while the shelf still showed ten units.
        checkoutService.prepareCheckout(userToken);
        assertEquals(9, productEntityRepository.findById(product.getId()).orElseThrow().getProductCount());

        assertThrows(ProductEditConflictException.class, () ->
                adminSupplyService.updateProduct(admin.getId(), product.getId(), "Stale", "general",
                        new BigDecimal("10.00"), 10, BigDecimal.ZERO, "stale", category.getCategoryId(), null, null, null, versionSeenByTheEditor));

        assertEquals(9, productEntityRepository.findById(product.getId()).orElseThrow().getProductCount(),
                "a rejected edit must leave the reservation exactly as it reserved it");
    }

    /**
     * The transaction-boundary race this whole feature exists for.
     *
     * <p>Asserted order-independently. Whichever of the two wins, the result must
     * be indistinguishable from some serial order:
     *
     * <ul>
     *   <li>edit first - it succeeds (the version still matches), then the
     *       reservation takes the shelf to 9;</li>
     *   <li>reservation first - it takes the shelf to 9 and advances the version,
     *       so the edit's stale count of 10 is rejected.</li>
     * </ul>
     *
     * <p>What must never happen is 10. That is the reservation being overwritten
     * back onto the shelf by a form that was opened before it, which is exactly
     * the oversell this prevents.
     */
    @Test
    void anEditRacingACheckoutPreparationNeverLosesTheReservation() throws Exception {
        ProductEntity product = createProduct("RacedEdit", "10.00", 10);
        UserEntity user = createUser("race-edit@migros.com", product.getId());
        String userToken = token(user);

        long versionSeenByTheEditor = jdbcTemplate.queryForObject(
                "SELECT version FROM product_entity WHERE product_entity_id = ?", Long.class,
                product.getId());
        AdminEntity admin = createAdmin("racing-editor");
        CategoryEntity category = createCategory(32);

        List<Boolean> results = runConcurrently(
                () -> attemptPrepare(userToken),
                () -> attemptStaleEdit(admin.getId(), product.getId(), category.getCategoryId(),
                        versionSeenByTheEditor, 10));

        assertTrue(results.get(0), "the reservation must succeed: stock is sufficient for either order");
        assertEquals(9, productEntityRepository.findById(product.getId()).orElseThrow().getProductCount(),
                "the reserved unit must stay reserved no matter which transaction won the race");

        long versionAfter = jdbcTemplate.queryForObject(
                "SELECT version FROM product_entity WHERE product_entity_id = ?", Long.class,
                product.getId());
        assertTrue(versionAfter > versionSeenByTheEditor,
                "at least one of the two writers must have advanced the version");
    }

    private boolean attemptStaleEdit(Long adminId, Long productId, int categoryId,
                                     long expectedVersion, int absoluteCount) {
        try {
            adminSupplyService.updateProduct(adminId, productId, "Raced", "general",
                    new BigDecimal("10.00"), absoluteCount, BigDecimal.ZERO, "raced", categoryId,
                    null, null, null, expectedVersion);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private AdminEntity createAdmin(String name) {
        AdminEntity admin = new AdminEntity();
        admin.setAdminName(name);
        admin.setItemEntities(new ArrayList<>());
        return adminEntityRepository.saveAndFlush(admin);
    }

    private CategoryEntity createCategory(int categoryId) {
        CategoryEntity category = new CategoryEntity();
        category.setCategoryId(categoryId);
        category.setCategoryName("race-category-" + categoryId);
        return categoryEntityRepository.saveAndFlush(category);
    }
}
