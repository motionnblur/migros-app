package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.payment.CheckoutResponseDto;
import com.example.MigrosBackend.dto.user.product.UserCartItemDto;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.payment.CheckoutService;
import org.junit.jupiter.api.AfterEach;
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

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

/**
 * The cart is rendered from the stored list but checkout reserves from the stored
 * list too, and for a long time only the rendering knew how to cope with an entry
 * that can no longer be bought.
 *
 * <p>The workflow that breaks is the ordinary one: the customer opens their cart,
 * it looks complete, they press checkout, and the request is refused for a line
 * they cannot see and cannot remove - the only affordance the UI has is the
 * remove button on a row that was never rendered. Deleted products, sold-out
 * products and quantities above the remaining stock all produce that state, and
 * all of them arrive without the customer doing anything wrong: somebody else
 * sold the last unit, or an administrator removed a product, between the add and
 * the checkout.
 *
 * <p>Making the read write the repaired list would close it and reopen an older
 * bug: a display request that persists the list it read erases a concurrent add.
 * So the read stays pure and the repair is its own explicit mutation,
 * {@code reconcileCart}, which reports exactly what it removed and what it
 * reduced. These tests are written as workflows - render, then recover, then
 * checkout - because neither half of the defect is visible in isolation: the read
 * is correct, the checkout is correct, and it is their disagreement about a cart
 * that cannot be bought that strands the customer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class CartReconciliationPostgresTest {

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
        // These tests create real checkouts and then read the cart and the shelf
        // at a precise moment, so the background expiry sweep must not race them.
        registry.add("payment.checkout.expiration-scan-ms", () -> "86400000");
        registry.add("payment.checkout.expiration-initial-delay-ms", () -> "86400000");
    }

    @Autowired
    private UserCartService userCartService;
    @Autowired
    private CheckoutService checkoutService;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private UserEntityRepository userEntityRepository;
    @Autowired
    private ProductEntityRepository productEntityRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DataSource dataSource;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @AfterEach
    void shutDownExecutor() {
        executor.shutdownNow();
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE user_entity, product_entity RESTART IDENTITY CASCADE");
    }

    // -------------------------------------------------------------------------
    // The defect, then the fix
    // -------------------------------------------------------------------------

    /**
     * The whole story in one test, because none of its three stages means
     * anything on its own.
     *
     * <p>Stage one is the state a customer actually gets into: they added two
     * products, and by the time they looked again one of them was sold out. The
     * cart renders as a single available line, so nothing looks wrong.
     *
     * <p>Stage two is the trap. Checkout reserves from the <em>stored</em> list, so
     * it still sees the sold-out entry and refuses the whole request. The pinned
     * {@code GeneralException} is not a test of checkout's correctness - it is the
     * proof that the rendered cart and the reserved cart are genuinely different
     * views of the same column, which is the defect.
     *
     * <p>Stage three is the fix: reconciliation removes the unbuyable entry from
     * the stored list and says so, after which the very same checkout succeeds and
     * reserves only what the customer could see.
     */
    @Test
    void aSoldOutEntryIsHiddenByTheReadBlocksCheckoutAndIsClearedByReconciliation() throws Exception {
        UserEntity user = createUser("recover@migros.com");
        ProductEntity available = createProduct("Available", 5);
        ProductEntity soldOut = createProduct("SoldOut", 0);
        storeCart(user, available.getId(), soldOut.getId());
        String userToken = token(user);

        // 1. Rendered: only the product that can actually be bought.
        List<UserCartItemDto> rendered = userCartService.getCartData(user.getUserMail());
        assertEquals(1, rendered.size(), "the pure read hides the sold-out entry, and must keep doing so");
        assertEquals(available.getId(), rendered.get(0).getProductId());

        // 2. Checkout still reserves from the stored list, so it fails for a line
        //    the customer cannot see. This is the broken state, pinned.
        assertThrows(GeneralException.class, () -> checkoutService.prepareCheckout(userToken),
                "the rendered cart and the reserved cart disagree; that disagreement is the defect");

        // 3. Reconciliation repairs the stored list and reports what it dropped.
        UserCartService.CartReconciliation reconciliation =
                userCartService.reconcileCart(user.getUserMail());

        assertEquals(List.of(soldOut.getId()), reconciliation.removedProductIds());
        assertTrue(reconciliation.reducedProductIds().isEmpty());
        assertEquals(List.of(available.getId()), storedCart(user.getId()),
                "the unbuyable entry must be gone from the column checkout actually reads");
        assertEquals(1, reconciliation.cart().size());
        assertEquals(available.getId(), reconciliation.cart().get(0).getProductId());
        assertEquals(1, reconciliation.cart().get(0).getProductCount());

        CheckoutResponseDto prepared = checkoutService.prepareCheckout(userToken);

        assertNotNull(prepared.checkoutId());
        assertEquals(4, stockOf(available.getId()),
                "the reservation deducted exactly the one unit the reconciled cart held");
    }

    /**
     * A deleted product is the same trap with no stock to explain it: there is
     * nothing left to buy at any quantity, and the row the customer would need to
     * remove it from is never rendered.
     */
    @Test
    void aDeletedProductIsRemovedAndStopsBlockingCheckout() throws Exception {
        UserEntity user = createUser("deleted@migros.com");
        ProductEntity survivor = createProduct("Survivor", 4);
        ProductEntity doomed = createProduct("Doomed", 9);
        storeCart(user, survivor.getId(), doomed.getId());
        String userToken = token(user);

        // An administrator deletes it while it sits in somebody's cart.
        productEntityRepository.deleteById(doomed.getId());
        productEntityRepository.flush();

        assertEquals(1, userCartService.getCartData(user.getUserMail()).size(),
                "a product with no row cannot be rendered");

        UserCartService.CartReconciliation reconciliation =
                userCartService.reconcileCart(user.getUserMail());

        assertEquals(List.of(doomed.getId()), reconciliation.removedProductIds());
        assertEquals(List.of(survivor.getId()), storedCart(user.getId()));

        checkoutService.prepareCheckout(userToken);

        assertEquals(3, stockOf(survivor.getId()),
                "checkout now reserves only the entry the customer could see");
    }

    /**
     * A quantity above the remaining stock is a different repair from a removal,
     * and the two are reported separately precisely so the customer can tell them
     * apart.
     *
     * <p>Zeroing it would throw away something the customer can still buy, and
     * leaving it as it is would keep the checkout failing. The only value that is
     * both purchasable and within what was previously chosen is the clamp, so the
     * stored quantity becomes exactly the stock that is left - and the product
     * stays in the cart rather than being reported as removed.
     */
    @Test
    void aQuantityAboveStockIsReducedAndNeverZeroedOrLeftAlone() throws Exception {
        UserEntity user = createUser("reduced@migros.com");
        ProductEntity scarce = createProduct("Scarce", 2);
        storeCart(user, scarce.getId(), scarce.getId(), scarce.getId(), scarce.getId(), scarce.getId());

        UserCartService.CartReconciliation reconciliation =
                userCartService.reconcileCart(user.getUserMail());

        assertEquals(List.of(scarce.getId()), reconciliation.reducedProductIds(),
                "a clamp is a reduction, and must be reported as one");
        assertFalse(reconciliation.removedProductIds().contains(scarce.getId()),
                "a product that can still be bought must never be reported as removed");

        assertEquals(List.of(scarce.getId(), scarce.getId()), storedCart(user.getId()),
                "the stored quantity is lowered to what is actually available, not to nothing");

        assertEquals(1, reconciliation.cart().size());
        assertEquals(2, reconciliation.cart().get(0).getProductCount());
        assertEquals(2, reconciliation.cart().get(0).getAvailableStock());
    }

    /**
     * A consistent cart is left completely alone, including on disk.
     *
     * <p>Not dirtied, not rewritten: the row's {@code xmin} is unchanged, which
     * means PostgreSQL did not create a new row version at all. That matters
     * because reconciliation is a repair, and a repair that writes on every visit
     * turns a customer opening their cart into a writer contending with their own
     * cart changes - the same trade-off the pure read was made to avoid.
     */
    @Test
    void aHealthyCartIsNeitherReportedChangedNorWritten() throws Exception {
        UserEntity user = createUser("healthy@migros.com");
        ProductEntity plentiful = createProduct("Plentiful", 10);
        storeCart(user, plentiful.getId(), plentiful.getId());

        List<Long> storedBefore = storedCart(user.getId());
        long rowTransactionBefore = cartRowTransactionId(user.getId());

        UserCartService.CartReconciliation reconciliation =
                userCartService.reconcileCart(user.getUserMail());

        assertTrue(reconciliation.removedProductIds().isEmpty());
        assertTrue(reconciliation.reducedProductIds().isEmpty());
        assertEquals(1, reconciliation.cart().size());
        assertEquals(plentiful.getId(), reconciliation.cart().get(0).getProductId());
        assertEquals(2, reconciliation.cart().get(0).getProductCount());
        assertEquals(10, reconciliation.cart().get(0).getAvailableStock());

        assertEquals(storedBefore, storedCart(user.getId()));
        assertEquals(rowTransactionBefore, cartRowTransactionId(user.getId()),
                "a cart that needed no repair must not produce a new row version at all");
    }

    /**
     * The reason reconciliation takes the user row's write lock instead of
     * reading and writing like the display path it replaces.
     *
     * <p>The cart is one list column, so reconciliation is a read-modify-write
     * like every other cart writer. An addition that commits while it is running
     * must not be erased by the stale list it read. The interleaving is produced
     * rather than hoped for: the user row's write lock is held on a connection of
     * our own, reconciliation is started and observed to be queued behind it, and
     * only then is the concurrent addition issued. Both requests are therefore
     * waiting for the same row before either holds it, and PostgreSQL grants a
     * contended row lock in the order the waiters queued - so reconciliation,
     * which queued first, reads and writes the pre-addition cart, and the
     * addition then reads the reconciled one.
     *
     * <p>That is the adversarial order for the assertion: reconciliation's write
     * is the one that lands between the addition's request and its completion, and
     * the addition still survives. The complementary order - addition first, so
     * reconciliation simply reads the addition in and keeps it - is pinned by
     * {@link #aReconciliationThatObservesAConcurrentAdditionKeepsIt()}.
     */
    @Test
    void aConcurrentAdditionIsNotErasedByReconciliation() throws Exception {
        UserEntity user = createUser("concurrent@migros.com");
        ProductEntity scarce = createProduct("Scarce", 1);
        ProductEntity added = createProduct("Added", 5);
        storeCart(user, scarce.getId(), scarce.getId(), scarce.getId());

        Future<UserCartService.CartReconciliation> reconciliationFuture;
        Future<Boolean> additionFuture;
        try (Connection blocker = holdUserRowLock(user.getId())) {
            // Queued first, and observed to be waiting rather than running.
            reconciliationFuture = executor.submit(() -> userCartService.reconcileCart(user.getUserMail()));
            assertTrue(blocksOn(reconciliationFuture),
                    "reconciliation must take the same user row write lock as every other cart writer, "
                            + "or it can write a stale list over a concurrent change");

            // Queued second, behind the same row.
            additionFuture = executor.submit(() -> attempt(
                    () -> userCartService.addProductToCart(added.getId(), user.getUserMail())));
        }

        UserCartService.CartReconciliation reconciliation =
                reconciliationFuture.get(30, TimeUnit.SECONDS);
        assertTrue(additionFuture.get(30, TimeUnit.SECONDS),
                "adding a product with five units of stock to a cart that holds one must succeed "
                        + "regardless of the interleaving");

        assertTrue(reconciliation.removedProductIds().isEmpty(),
                "a product that can still be bought is reduced, never removed");
        assertEquals(List.of(scarce.getId()), reconciliation.reducedProductIds(),
                "reconciliation ran against the pre-addition cart, so it reduced the original entry");
        assertEquals(List.of(scarce.getId(), added.getId()), storedCart(user.getId()),
                "the concurrent addition must survive: reconciliation wrote the list it read under the "
                        + "row lock, and the addition re-read the cart after it committed");
    }

    /**
     * The other serial order, for symmetry: the addition is queued first, so
     * reconciliation reads a cart that already contains it. A reconciliation that
     * dropped everything it did not itself put in the cart would lose the
     * addition here, and the end state would still look plausible.
     */
    @Test
    void aReconciliationThatObservesAConcurrentAdditionKeepsIt() throws Exception {
        UserEntity user = createUser("observed@migros.com");
        ProductEntity scarce = createProduct("Scarce", 1);
        ProductEntity added = createProduct("Added", 5);
        storeCart(user, scarce.getId(), scarce.getId(), scarce.getId());

        Future<Boolean> additionFuture;
        Future<UserCartService.CartReconciliation> reconciliationFuture;
        try (Connection blocker = holdUserRowLock(user.getId())) {
            additionFuture = executor.submit(() -> attempt(
                    () -> userCartService.addProductToCart(added.getId(), user.getUserMail())));
            assertTrue(blocksOn(additionFuture),
                    "precondition: the addition is queued behind the externally held row lock");

            reconciliationFuture = executor.submit(() -> userCartService.reconcileCart(user.getUserMail()));
        }

        assertTrue(additionFuture.get(30, TimeUnit.SECONDS));
        UserCartService.CartReconciliation reconciliation =
                reconciliationFuture.get(30, TimeUnit.SECONDS);

        assertEquals(List.of(scarce.getId()), reconciliation.reducedProductIds());
        assertTrue(reconciliation.removedProductIds().isEmpty());
        assertEquals(List.of(scarce.getId(), added.getId()), storedCart(user.getId()),
                "reconciliation read the addition into its own write, so the addition is preserved "
                        + "alongside the reduced original entry");
    }

    /**
     * Reconciliation is a repair, not a purchase. It must not reserve, must not
     * create an order, and must not move money - a cart that was cleaned up has
     * bought nothing, and a repair that silently reserved stock would take units
     * off the shelf for a customer who never asked for them.
     */
    @Test
    void reconciliationReservesNothingAndCreatesNoCheckout() throws Exception {
        UserEntity user = createUser("no-charge@migros.com");
        ProductEntity scarce = createProduct("Scarce", 2);
        ProductEntity soldOut = createProduct("SoldOut", 0);
        storeCart(user, scarce.getId(), scarce.getId(), scarce.getId(), soldOut.getId());

        int scarceStockBefore = stockOf(scarce.getId());
        int soldOutStockBefore = stockOf(soldOut.getId());

        UserCartService.CartReconciliation reconciliation =
                userCartService.reconcileCart(user.getUserMail());

        assertEquals(List.of(soldOut.getId()), reconciliation.removedProductIds());
        assertEquals(List.of(scarce.getId()), reconciliation.reducedProductIds());
        assertEquals(scarceStockBefore, stockOf(scarce.getId()),
                "a repair must leave the shelf exactly as it found it");
        assertEquals(soldOutStockBefore, stockOf(soldOut.getId()));
        assertEquals(0, countRows("checkout_entity"), "no reservation may be created by a repair");
        assertEquals(0, countRows("checkout_item_entity"));
        assertEquals(0, countRows("order_entity"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean attempt(Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Holds the user row's write lock on an independent connection, so the
     * interleaving a test needs is produced rather than hoped for.
     */
    private Connection holdUserRowLock(Long userId) throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM user_entity WHERE user_entity_id = ? FOR UPDATE")) {
            statement.setLong(1, userId);
            statement.executeQuery();
        }
        return connection;
    }

    /** A task that has not completed within the window is waiting on the lock. */
    private boolean blocksOn(Future<?> future) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BLOCK_OBSERVATION_MS);
        while (System.nanoTime() < deadline) {
            if (future.isDone()) {
                return false;
            }
            Thread.sleep(25);
        }
        return !future.isDone();
    }

    /**
     * The committed cart column, read over a connection of its own so it cannot
     * observe anything the calling transaction merely staged.
     */
    private List<Long> storedCart(Long userId) throws SQLException {
        List<Long> cart = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT products_ids_in_cart FROM user_entity WHERE user_entity_id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    Array array = rows.getArray(1);
                    if (array != null) {
                        cart.addAll(Arrays.asList((Long[]) array.getArray()));
                    }
                }
            }
        }
        return cart;
    }

    /**
     * The transaction that last wrote the user row. An {@code UPDATE} always
     * creates a new row version, so an unchanged value here means nothing wrote
     * the row at all - which a before/after comparison of the column itself
     * cannot distinguish from a write that happened to store the same list.
     */
    private long cartRowTransactionId(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT xmin::text::bigint FROM user_entity WHERE user_entity_id = ?",
                Long.class, userId);
    }

    private int stockOf(Long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT product_count FROM product_entity WHERE product_entity_id = ?",
                Integer.class, productId);
    }

    private int countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void storeCart(UserEntity user, Long... productIds) {
        user.setProductsIdsInCart(new ArrayList<>(Arrays.asList(productIds)));
        userEntityRepository.saveAndFlush(user);
    }

    private String token(UserEntity user) {
        return tokenService.generateUserToken(user.getUserMail());
    }

    private UserEntity createUser(String mail) {
        UserEntity user = new UserEntity();
        user.setUserMail(mail);
        user.setUserName("Tester");
        user.setBanned(false);
        user.setProductsIdsInCart(new ArrayList<>());
        return userEntityRepository.saveAndFlush(user);
    }

    private ProductEntity createProduct(String name) {
        return createProduct(name, 5);
    }

    private ProductEntity createProduct(String name, int stock) {
        ProductEntity product = new ProductEntity();
        product.setProductName(name);
        product.setSubcategoryName("general");
        product.setProductCount(stock);
        product.setProductPrice(new BigDecimal("10.00"));
        product.setProductDiscount(BigDecimal.ZERO);
        product.setProductDescription("test product");
        return productEntityRepository.saveAndFlush(product);
    }
}
