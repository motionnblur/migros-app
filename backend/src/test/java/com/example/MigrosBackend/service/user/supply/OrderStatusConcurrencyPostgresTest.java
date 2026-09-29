package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.order.OrderPageDto;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.OrderNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A user's cancellation and an administrator's status change both decide from
 * the order's status, and both may restock. They must be serialized by an order
 * row lock so exactly one of them can observe {@code Pending} and restock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class OrderStatusConcurrencyPostgresTest {

    private static final long BLOCK_OBSERVATION_MS = 750L;

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
    }

    @Autowired
    private UserSupplyService userSupplyService;
    @Autowired
    private UserOrderService userOrderService;
    @Autowired
    private UserEntityRepository userEntityRepository;
    @Autowired
    private ProductEntityRepository productEntityRepository;
    @Autowired
    private OrderGroupEntityRepository orderGroupEntityRepository;
    @Autowired
    private OrderEntityRepository orderEntityRepository;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager txManager;


    private final ExecutorService executor = Executors.newCachedThreadPool();

    @AfterEach
    void shutDownExecutor() {
        executor.shutdownNow();
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE checkout_item_entity, checkout_entity, order_entity, "
                + "order_group_entity, product_entity, user_entity RESTART IDENTITY CASCADE");
    }

    /**
     * The repository must take a <em>write</em> lock on the order row, not merely
     * read it.
     *
     * <p>This is asserted directly against the lock rather than through the
     * service. A service-level timing test cannot distinguish a locking read
     * from an ordinary one here: both block eventually, because the later
     * {@code DELETE} of the same row blocks too. Only the read itself is
     * observable, and only a {@code SELECT ... FOR UPDATE} blocks while another
     * connection holds the row. The companion unit tests
     * ({@code UserSupplyServiceTest}, {@code UserOrderServiceTest}) prove the
     * services call these locking methods, so the two together establish that
     * the status is read under the lock.
     */
    @Test
    void theOrderGroupLookupBlocksWhileAnotherConnectionHoldsTheRow() throws Exception {
        UserEntity user = createUser("repo-group@migros.com");
        ProductEntity product = createProduct("RepoGroup", "10.00", 5);
        OrderGroupEntity group = createPendingGroup(user, product, 1);

        Future<Boolean> lookup;
        try (Connection blocker = holdOrderGroupRowLock(group.getId())) {
            lookup = submit(() -> inTransaction(() ->
                    nonNull(orderGroupEntityRepository.findByIdAndUserIdForUpdate(group.getId(), user.getId()))));
            assertTrue(blocksOn(lookup),
                    "findByIdAndUserIdForUpdate must block while the row is locked, "
                            + "otherwise a concurrent status change can interleave between the check and the restock");
        }
        assertTrue(cancellationResult(lookup));
    }

    @Test
    void theAdminOrderGroupLookupBlocksWhileAnotherConnectionHoldsTheRow() throws Exception {
        UserEntity user = createUser("repo-admin@migros.com");
        ProductEntity product = createProduct("RepoAdmin", "10.00", 5);
        OrderGroupEntity group = createPendingGroup(user, product, 1);

        Future<Boolean> lookup;
        try (Connection blocker = holdOrderGroupRowLock(group.getId())) {
            lookup = submit(() -> inTransaction(() ->
                    nonNull(orderGroupEntityRepository.findByIdForUpdate(group.getId()))));
            assertTrue(blocksOn(lookup),
                    "findByIdForUpdate must block while the row is locked");
        }
        assertTrue(cancellationResult(lookup));
    }

    @Test
    void theNonLockingGroupReadIsNotBlocked() throws Exception {
        UserEntity user = createUser("repo-read@migros.com");
        ProductEntity product = createProduct("RepoRead", "10.00", 5);
        OrderGroupEntity group = createPendingGroup(user, product, 1);

        try (Connection blocker = holdOrderGroupRowLock(group.getId())) {
            Future<Boolean> read = submit(() -> inTransaction(() ->
                    nonNull(orderGroupEntityRepository.findByIdAndUserId(group.getId(), user.getId()))));
            assertTrue(!blocksOn(read),
                    "the plain read used for display must stay non-blocking; only the "
                            + "status-deciding paths are allowed to take the write lock");
        }
    }

    @Test
    void theAdminOrderLookupBlocksWhileAnotherConnectionHoldsTheRow() throws Exception {
        UserEntity user = createUser("repo-order@migros.com");
        ProductEntity product = createProduct("RepoOrder", "10.00", 5);
        OrderGroupEntity group = createPendingGroup(user, product, 1);
        Long orderId = firstOrderId(group);

        Future<Boolean> lookup;
        try (Connection blocker = holdOrderEntityRowLock(orderId)) {
            lookup = submit(() -> inTransaction(() -> nonNull(orderEntityRepository.findByIdForUpdate(orderId))));
            assertTrue(blocksOn(lookup),
                    "findByIdForUpdate on an order must block while the row is locked");
        }
        assertTrue(cancellationResult(lookup));
    }

    @Test
    void theOwnedLegacyOrderLookupBlocksWhileAnotherConnectionHoldsTheRow() throws Exception {
        UserEntity user = createUser("repo-legacy-owned@migros.com");
        ProductEntity product = createProduct("RepoLegacyOwned", "10.00", 5);
        OrderEntity legacy = saveLegacyOrder(user, product, 1);

        Future<Boolean> lookup;
        try (Connection blocker = holdOrderEntityRowLock(legacy.getId())) {
            lookup = submit(() -> inTransaction(() ->
                    nonNull(orderEntityRepository.findByIdAndUserIdForUpdate(legacy.getId(), user.getId()))));
            assertTrue(blocksOn(lookup),
                    "findByIdAndUserIdForUpdate must block while the row is locked");
        }
        assertTrue(cancellationResult(lookup));
    }

    @Test
    void manyConcurrentCancellationsOfTheSameOrderRestoreStockExactlyOnce() throws Exception {

        UserEntity user = createUser("stampede@migros.com");
        ProductEntity product = createProduct("Stampede", "10.00", 1);
        OrderGroupEntity group = createPendingGroup(user, product, 1);
        String token = tokenService.generateUserToken(user.getUserMail());

        // Hold the row lock so every cancellation is already queued behind it;
        // they then run strictly one after another, which is the exact
        // interleaving the lock is supposed to produce.
        List<Future<Boolean>> queued = new ArrayList<>();
        try (Connection blocker = holdOrderGroupRowLock(group.getId())) {
            for (int i = 0; i < 6; i++) {
                queued.add(submit(() -> attempt(() -> userSupplyService.cancelOrder(group.getId(), token))));
            }
            assertTrue(blocksOn(queued.get(0)), "the first cancellation must wait for the row lock");
            Thread.sleep(200);
        }

        List<Boolean> results = new ArrayList<>();
        for (Future<Boolean> pending : queued) {
            results.add(pending.get(30, TimeUnit.SECONDS));
        }


        assertEquals(1, results.stream().filter(Boolean::booleanValue).count(),
                "only the winner of the race may cancel the order");
        assertEquals(2, productEntityRepository.findById(product.getId()).orElseThrow().getProductCount(),
                "stock must be returned exactly once no matter how many cancellations race");
        assertTrue(orderGroupEntityRepository.findById(group.getId()).isEmpty());
    }

    @Test
    void adminStatusChangeAppliesToTheHeaderAndEveryLine() {
        UserEntity user = createUser("status@migros.com");
        ProductEntity first = createProduct("StatusA", "10.00", 5);
        ProductEntity second = createProduct("StatusB", "10.00", 5);
        OrderGroupEntity group = createPendingGroup(user, first, 1, second, 2);

        userOrderService.updateOrderStatus(group.getId(), "Shipped");

        OrderGroupEntity reloadedGroup = orderGroupEntityRepository.findById(group.getId()).orElseThrow();
        assertEquals("Shipped", reloadedGroup.getStatus());
        List<OrderEntity> lines = orderEntityRepository.findByOrderGroup_Id(group.getId());
        assertEquals(2, lines.size());
        for (OrderEntity line : lines) {
            assertEquals("Shipped", line.getStatus(),
                    "a header and line status disagreement is what the single transaction prevents");
        }
    }

    @Test
    void adminStatusChangeToAnyStringIsStoredVerbatim() {
        UserEntity user = createUser("verbatim@migros.com");
        ProductEntity product = createProduct("Verbatim", "10.00", 5);
        OrderGroupEntity group = createPendingGroup(user, product, 1);

        userOrderService.updateOrderStatus(group.getId(), "any-status-string");

        assertEquals("any-status-string",
                orderGroupEntityRepository.findById(group.getId()).orElseThrow().getStatus());
    }

    @Test
    void deletingANonPendingOrderDoesNotRestock() {
        UserEntity user = createUser("shipped@migros.com");
        ProductEntity product = createProduct("Shipped", "10.00", 1);
        OrderGroupEntity group = createPendingGroup(user, product, 1);
        userOrderService.updateOrderStatus(group.getId(), "Shipped");

        userOrderService.deleteOrder(group.getId());

        assertEquals(1, productEntityRepository.findById(product.getId()).orElseThrow().getProductCount(),
                "stock reserved by a shipped order must not return to the shelf");
    }

    @Test
    void cancellingANonPendingOrderIsRejected() {
        UserEntity user = createUser("late-cancel@migros.com");
        ProductEntity product = createProduct("LateCancel", "10.00", 5);
        OrderGroupEntity group = createPendingGroup(user, product, 1);
        userOrderService.updateOrderStatus(group.getId(), "Shipped");
        String token = tokenService.generateUserToken(user.getUserMail());

        assertThrows(GeneralException.class,
                () -> userSupplyService.cancelOrder(group.getId(), token));

        assertEquals(5, productEntityRepository.findById(product.getId()).orElseThrow().getProductCount());
    }

    @Test
    void aLegacyOrderIsLockedAndRestockedOnCancellation() {
        UserEntity user = createUser("legacy@migros.com");
        ProductEntity product = createProduct("Legacy", "10.00", 1);
        OrderEntity legacy = new OrderEntity();
        legacy.setUserId(user.getId());
        legacy.setUserEntity(user);
        legacy.setItemId(product.getId());
        legacy.setCount(1);
        legacy.setPrice(new BigDecimal("10.00"));
        legacy.setTotalPrice(new BigDecimal("10.00"));
        legacy.setStatus("Pending");
        orderEntityRepository.saveAndFlush(legacy);
        String token = tokenService.generateUserToken(user.getUserMail());

        userSupplyService.cancelOrder(legacy.getId(), token);

        assertEquals(2, productEntityRepository.findById(product.getId()).orElseThrow().getProductCount());
        assertTrue(orderEntityRepository.findById(legacy.getId()).isEmpty());
    }

    @Test
    void aConcurrentLegacyCancellationRestoresStockOnce() throws Exception {
        UserEntity user = createUser("legacy-race@migros.com");
        ProductEntity product = createProduct("LegacyRace", "10.00", 1);
        OrderEntity legacy = new OrderEntity();
        legacy.setUserId(user.getId());
        legacy.setUserEntity(user);
        legacy.setItemId(product.getId());
        legacy.setCount(1);
        legacy.setPrice(new BigDecimal("10.00"));
        legacy.setTotalPrice(new BigDecimal("10.00"));
        legacy.setStatus("Pending");
        orderEntityRepository.saveAndFlush(legacy);
        String token = tokenService.generateUserToken(user.getUserMail());

        List<Future<Boolean>> queued = new ArrayList<>();
        try (Connection blocker = holdOrderEntityRowLock(legacy.getId())) {
            queued.add(submit(() -> attempt(() -> userSupplyService.cancelOrder(legacy.getId(), token))));
            queued.add(submit(() -> attempt(() -> userSupplyService.cancelOrder(legacy.getId(), token))));
            assertTrue(blocksOn(queued.get(0)), "a legacy cancellation must wait for the row lock");
        }

        int winners = 0;
        for (Future<Boolean> result : queued) {
            if (result.get(30, TimeUnit.SECONDS)) {
                winners++;
            }
        }

        assertEquals(1, winners, "only the winner of the race may cancel the legacy order");
        assertEquals(2, productEntityRepository.findById(product.getId()).orElseThrow().getProductCount());
    }


    @Test
    void theAdminOrderListingStillReportsTheSameStatuses() {
        UserEntity user = createUser("listing@migros.com");
        ProductEntity product = createProduct("Listing", "10.00", 5);
        OrderGroupEntity group = createPendingGroup(user, product, 2);
        userOrderService.updateOrderStatus(group.getId(), "Shipped");

        OrderPageDto page = userOrderService.getAllOrders(0, 10);

        assertEquals(1, page.getItems().size());
        assertEquals("Shipped", page.getItems().get(0).getStatus());
        assertEquals(0, new BigDecimal("20.00").compareTo(page.getItems().get(0).getTotalPrice()));
    }

    @Test
    void deletingAnUnknownOrderReportsNotFound() {
        assertThrows(OrderNotFoundException.class,
                () -> userOrderService.deleteOrder(987654L));
    }

    private boolean attempt(Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean nonNull(Object value) {
        return value != null;
    }

    private Future<Boolean> submit(Callable<Boolean> task) {
        return executor.submit(task);
    }

    /**
     * A pessimistic-lock query is only valid inside a transaction. The services
     * supply one ({@code @Transactional}); this supplies it for the repository
     * contract tests.
     */
    private boolean inTransaction(java.util.function.Supplier<Boolean> work) {
        return new org.springframework.transaction.support.TransactionTemplate(txManager)
                .execute(status -> work.get());
    }


    private static boolean cancellationResult(Future<Boolean> future) throws Exception {
        return future.get(30, TimeUnit.SECONDS);
    }

    private static boolean statusChangeResult(Future<Boolean> future) throws Exception {
        return future.get(30, TimeUnit.SECONDS);
    }

    private static boolean deletionResult(Future<Boolean> future) throws Exception {
        return future.get(30, TimeUnit.SECONDS);
    }

    /**
     * Holds the order-group row's write lock on an independent connection.
     *
     * <p>Starting two operations from a barrier is not enough to prove they
     * serialize: without a lock the operations are simply too fast and usually
     * finish one after the other anyway, so the race is never observed. Taking
     * the row lock from outside makes the interleaving deterministic — a
     * correctly locked service call provably waits, and an unlocked one provably
     * does not.
     */
    private Connection holdOrderGroupRowLock(Long orderGroupId) throws SQLException {
        return holdLock("SELECT 1 FROM order_group_entity WHERE order_group_entity_id = ? FOR UPDATE",
                orderGroupId);
    }

    private Connection holdOrderEntityRowLock(Long orderId) throws SQLException {
        return holdLock("SELECT 1 FROM order_entity WHERE order_entity_id = ? FOR UPDATE", orderId);
    }

    private Connection holdLock(String sql, Long id) throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.executeQuery();
        }
        return connection;
    }

    /** A task that has not completed within the window is waiting on the lock. */
    private boolean blocksOn(Future<Boolean> future) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BLOCK_OBSERVATION_MS);
        while (System.nanoTime() < deadline) {
            if (future.isDone()) {
                return false;
            }
            Thread.sleep(25);
        }
        return !future.isDone();
    }

    private Long firstOrderId(OrderGroupEntity group) {
        return orderEntityRepository.findByOrderGroup_Id(group.getId()).get(0).getId();
    }

    private OrderEntity saveLegacyOrder(UserEntity user, ProductEntity product, int count) {
        OrderEntity legacy = new OrderEntity();
        legacy.setUserId(user.getId());
        legacy.setUserEntity(user);
        legacy.setItemId(product.getId());
        legacy.setCount(count);
        legacy.setPrice(product.getProductPrice());
        legacy.setTotalPrice(product.getProductPrice().multiply(BigDecimal.valueOf(count)));
        legacy.setStatus("Pending");
        return orderEntityRepository.saveAndFlush(legacy);
    }

    private UserEntity createUser(String mail) {

        UserEntity user = new UserEntity();
        user.setUserMail(mail);
        user.setBanned(false);
        user.setProductsIdsInCart(new ArrayList<>());
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

    private OrderGroupEntity createPendingGroup(UserEntity user, ProductEntity first, int firstCount) {
        return createPendingGroup(user, first, firstCount, first, 0);
    }

    private OrderGroupEntity createPendingGroup(UserEntity user, ProductEntity first, int firstCount,
                                               ProductEntity second, int secondCount) {
        OrderGroupEntity group = new OrderGroupEntity();
        group.setUserEntity(user);
        group.setUserId(user.getId());
        group.setCreatedAt(java.time.LocalDateTime.now());
        group.setStatus("Pending");
        group = orderGroupEntityRepository.saveAndFlush(group);

        if (firstCount > 0) {
            saveLine(group, user, first, firstCount);
        }
        if (secondCount > 0) {
            saveLine(group, user, second, secondCount);
        }
        return group;
    }

    private void saveLine(OrderGroupEntity group, UserEntity user, ProductEntity product, int count) {
        OrderEntity line = new OrderEntity();
        line.setUserEntity(user);
        line.setOrderGroup(group);
        line.setUserId(user.getId());
        line.setItemId(product.getId());
        line.setCount(count);
        line.setPrice(product.getProductPrice());
        line.setTotalPrice(product.getProductPrice().multiply(BigDecimal.valueOf(count)));
        line.setStatus(group.getStatus());
        orderEntityRepository.saveAndFlush(line);
    }
}
