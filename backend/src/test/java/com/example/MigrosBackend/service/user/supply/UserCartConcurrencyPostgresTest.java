package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.user.product.UserCartItemDto;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.helper.ProductPricingPolicy;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cart is a single list column on the user row, so every mutation is a
 * read-modify-write. Two concurrent requests used to read the same starting
 * cart and the second silently overwrote the first, losing an update. The cart
 * mutations must serialize on the user row (pessimistic lock + transaction) so
 * both effects survive.
 *
 * <p>The read is held to a second rule: it must write nothing at all. It used to
 * persist the normalized cart as a side effect, so a display request that read
 * before an add committed would write the pre-add list back and erase it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class UserCartConcurrencyPostgresTest {

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
    private UserCartService userCartService;
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

    @Test
    void concurrentAddsOfDifferentProductsBothPersist() throws Exception {
        UserEntity user = createUser("cart-race@migros.com");
        ProductEntity first = createProduct("CartFirst");
        ProductEntity second = createProduct("CartSecond");
        String userMail = user.getUserMail();

        CountDownLatch start = new CountDownLatch(1);
        Future<Void> firstAdd = executor.submit(() -> {
            start.await();
            userCartService.addProductToCart(first.getId(), userMail);
            return null;
        });
        Future<Void> secondAdd = executor.submit(() -> {
            start.await();
            userCartService.addProductToCart(second.getId(), userMail);
            return null;
        });
        start.countDown();

        firstAdd.get(30, TimeUnit.SECONDS);
        secondAdd.get(30, TimeUnit.SECONDS);

        List<Long> cart = storedCart(user.getId());
        assertNotNull(cart);
        assertEquals(2, cart.size(),
                "both concurrent additions must survive; a lost update drops one");
        assertTrue(cart.contains(first.getId()));
        assertTrue(cart.contains(second.getId()));
    }

    /**
     * The response is still normalized - the deleted product and the
     * over-requested quantity are clamped away - but nothing about the stored
     * list changes. The column is read over a connection of its own, so a write
     * that happened only inside an uncommitted transaction could not pass.
     */
    @Test
    void readingACartWithStaleEntriesNormalizesTheResponseAndPersistsNothing() throws Exception {
        UserEntity user = createUser("stale@migros.com");
        ProductEntity plentiful = createProduct("Plentiful", 10);
        ProductEntity scarce = createProduct("Scarce", 1);
        ProductEntity soldOut = createProduct("SoldOut", 0);
        Long deletedProductId = createProduct("Deleted", 10).getId();

        // Three of a product with plenty of stock, three of a product with one
        // unit left, one of a sold-out product, and one that no longer exists.
        user.setProductsIdsInCart(new ArrayList<>(List.of(
                plentiful.getId(), plentiful.getId(), plentiful.getId(),
                scarce.getId(), scarce.getId(), scarce.getId(),
                soldOut.getId(), deletedProductId)));
        userEntityRepository.saveAndFlush(user);
        productEntityRepository.deleteById(deletedProductId);
        productEntityRepository.flush();

        List<Long> before = storedCart(user.getId());
        assertEquals(8, before.size());

        List<UserCartItemDto> rendered = userCartService.getCartData(user.getUserMail());

        assertEquals(2, rendered.size(), "the deleted and the sold-out product are omitted");
        assertEquals(plentiful.getId(), rendered.get(0).getProductId());
        assertEquals(3, rendered.get(0).getProductCount());
        assertEquals(10, rendered.get(0).getAvailableStock());
        assertEquals(scarce.getId(), rendered.get(1).getProductId());
        assertEquals(1, rendered.get(1).getProductCount(), "the quantity is clamped to available stock");

        assertEquals(before, storedCart(user.getId()),
                "a cart read must not persist the normalized list it computed");
    }

    /**
     * The read must not take the mutation lock either. A cart GET that queued
     * behind the user row would turn every display request into a writer
     * contending with the customer's own cart changes.
     */
    @Test
    void aCartReadDoesNotQueueBehindTheCartRowLock() throws Exception {
        UserEntity user = createUser("read-lock@migros.com");
        ProductEntity inCart = createProduct("InCart", 10);
        user.setProductsIdsInCart(new ArrayList<>(List.of(inCart.getId())));
        userEntityRepository.saveAndFlush(user);

        Future<List<UserCartItemDto>> read;
        try (Connection blocker = holdUserRowLock(user.getId())) {
            read = executor.submit(() -> userCartService.getCartData(user.getUserMail()));
            assertTrue(!blocksOn(read),
                    "getCartData must not take the user row write lock; only the mutations may");
        }
        assertEquals(1, read.get(30, TimeUnit.SECONDS).size());
    }

    /**
     * A read that overlaps an addition must not erase it. The row is locked from
     * outside, so the read is provably sequenced before the add rather than
     * merely racing it; the stored list additionally still holds a product that
     * has since been deleted, which is exactly the state whose normalization used
     * to be written back.
     */
    @Test
    void aCartReadOverlappingAnAdditionCannotEraseTheAddition() throws Exception {
        UserEntity user = createUser("read-add@migros.com");
        ProductEntity vanished = createProduct("Vanished", 10);
        ProductEntity added = createProduct("Added", 10);
        user.setProductsIdsInCart(new ArrayList<>(List.of(vanished.getId())));
        userEntityRepository.saveAndFlush(user);
        productEntityRepository.deleteById(vanished.getId());
        productEntityRepository.flush();

        Future<List<UserCartItemDto>> read;
        try (Connection blocker = holdUserRowLock(user.getId())) {
            read = executor.submit(() -> userCartService.getCartData(user.getUserMail()));
            assertTrue(!blocksOn(read));
        }
        assertTrue(read.get(30, TimeUnit.SECONDS).isEmpty(),
                "the deleted product is omitted from the response");

        userCartService.addProductToCart(added.getId(), user.getUserMail());

        assertEquals(List.of(vanished.getId(), added.getId()), storedCart(user.getId()),
                "the overlapping read must leave the addition alone; only the addition may have been written");
    }

    /**
     * Clear and add are both read-modify-write on the same column, so they have
     * to serialize. Whichever order the lock picks, the end state has to be one
     * of the two serial results - never a mixture, and never a lost write.
     */
    @Test
    void clearVersusAddProducesOneOfTheTwoSerialResults() throws Exception {
        UserEntity user = createUser("clear-add@migros.com");
        ProductEntity survivor = createProduct("Survivor", 10);
        ProductEntity raced = createProduct("Raced", 10);
        user.setProductsIdsInCart(new ArrayList<>(List.of(survivor.getId())));
        userEntityRepository.saveAndFlush(user);

        List<Boolean> results = runConcurrently(
                () -> attempt(() -> userCartService.clearUserCart(user.getUserMail())),
                () -> attempt(() -> userCartService.addProductToCart(raced.getId(), user.getUserMail())));

        assertTrue(results.stream().allMatch(Boolean::booleanValue),
                "neither operation may fail: they take the same lock, so neither is a lost update");

        List<Long> cart = storedCart(user.getId());
        List<Long> clearedThenAdded = List.of(raced.getId());
        List<Long> addedThenCleared = List.of();
        assertTrue(cart.equals(clearedThenAdded) || cart.equals(addedThenCleared),
                "the end state must correspond to one of the two serial orders, but was " + cart);
    }

    /**
     * The clear is a mutation and has to queue behind the same row lock as an
     * add. Holding the row from outside makes that observable: without the lock
     * the clear would read the pre-add list and write it straight back.
     */
    @Test
    void clearWaitsForTheCartRowLock() throws Exception {
        UserEntity user = createUser("clear-lock@migros.com");
        ProductEntity raced = createProduct("ClearLock", 10);
        user.setProductsIdsInCart(new ArrayList<>());
        userEntityRepository.saveAndFlush(user);

        Future<Boolean> clear;
        try (Connection blocker = holdUserRowLock(user.getId())) {
            clear = executor.submit(() -> attempt(() -> userCartService.clearUserCart(user.getUserMail())));
            assertTrue(blocksOn(clear),
                    "clearUserCart must take the same user row write lock as add/remove/count");
        }
        assertTrue(clear.get(30, TimeUnit.SECONDS));

        userCartService.addProductToCart(raced.getId(), user.getUserMail());
        assertEquals(List.of(raced.getId()), storedCart(user.getId()));
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
        product.setEffectivePrice(
                ProductPricingPolicy.effectivePrice(product.getProductPrice(), product.getProductDiscount()));
        product.setProductDescription("test product");
        return productEntityRepository.saveAndFlush(product);
    }
}
