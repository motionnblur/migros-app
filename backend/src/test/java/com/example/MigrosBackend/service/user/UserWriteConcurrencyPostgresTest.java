package com.example.MigrosBackend.service.user;

import com.example.MigrosBackend.dto.payment.CheckoutResponseDto;
import com.example.MigrosBackend.dto.user.sign.ResetPasswordDto;
import com.example.MigrosBackend.entity.checkout.CheckoutEntity;
import com.example.MigrosBackend.entity.checkout.CheckoutItemEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.PendingSignupEntity;
import com.example.MigrosBackend.entity.user.PendingTokenPurpose;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.helper.ProductPricingPolicy;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.CheckoutEntityRepository;
import com.example.MigrosBackend.repository.user.CheckoutItemEntityRepository;
import com.example.MigrosBackend.repository.user.PendingSignupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.support.SupportModerationService;
import com.example.MigrosBackend.service.user.payment.CheckoutService;
import com.example.MigrosBackend.service.user.profile.UserProfileService;
import com.example.MigrosBackend.service.user.sign.UserSignupService;
import com.example.MigrosBackend.service.user.supply.UserCartService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * Four unrelated requests write the same user row: the cart, the profile, the
 * password and the moderation ban flag. Each of them used to load the whole
 * entity and save it back, so whichever of them committed last put every other
 * column back exactly as it had read it, silently reverting the others' work.
 *
 * <p>These are cross-service scenarios, so they run against a real PostgreSQL
 * schema rather than in a mock. The interleavings are produced by holding the
 * row's write lock from an independent connection, which makes a correctly
 * column-scoped write provably coexist with the others; the read-then-write
 * splits that a lock alone cannot stage are produced by pausing a specific
 * repository call after it has read the row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class UserWriteConcurrencyPostgresTest {

    private static final long BLOCK_OBSERVATION_MS = 750L;

    private static final String USER_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String ADMIN_SECRET = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";
    private static final String STRONG_PASSWORD = "Rotated123!";
    private static final String OTHER_STRONG_PASSWORD = "Another456!";

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
        registry.add("app.frontend-base-url", () -> "http://localhost:4200");
        registry.add("app.backend-base-url", () -> "http://localhost:8080");
        registry.add("payment.checkout.expiration-scan-ms", () -> "86400000");
        registry.add("payment.checkout.expiration-initial-delay-ms", () -> "86400000");
    }

    @MockitoSpyBean
    private UserEntityRepository userEntityRepository;

    @Autowired
    private UserCartService userCartService;
    @Autowired
    private UserProfileService userProfileService;
    @Autowired
    private UserSignupService userSignupService;
    @Autowired
    private SupportModerationService supportModerationService;
    @Autowired
    private CheckoutService checkoutService;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private PendingSignupEntityRepository pendingSignupEntityRepository;
    @Autowired
    private ProductEntityRepository productEntityRepository;
    @Autowired
    private CheckoutEntityRepository checkoutEntityRepository;
    @Autowired
    private CheckoutItemEntityRepository checkoutItemEntityRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private EntityManager entityManager;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @AfterEach
    void shutDownExecutor() {
        executor.shutdownNow();
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE pending_signup_entity, checkout_item_entity, checkout_entity, "
                + "order_entity, order_group_entity, product_entity, user_entity RESTART IDENTITY CASCADE");
    }

    /**
     * Both writers are queued on the same row lock and released together. An
     * entity-wide profile write would have read the row before the addition and
     * would put the pre-add list back.
     */
    @Test
    void profileUpdateOverlappingACartMutationPreservesBothEffects() throws Exception {
        UserEntity user = createUser("profile-cart@migros.com");
        ProductEntity product = createProduct("CartPreserved", 10);
        String userMail = user.getUserMail();

        Future<Void> add;
        Future<Void> profile;
        try (Connection blocker = holdUserRowLock(user.getId())) {
            add = executor.submit(() -> {
                userCartService.addProductToCart(product.getId(), userMail);
                return null;
            });
            profile = executor.submit(() -> {
                uploadProfile(userMail);
                return null;
            });
            assertTrue(blocksOn(add), "the cart addition must wait for the user row lock");
            assertTrue(blocksOn(profile), "the profile update must wait for the user row lock too");
        }
        add.get(30, TimeUnit.SECONDS);
        profile.get(30, TimeUnit.SECONDS);

        assertEquals(List.of(product.getId()), storedCart(user.getId()),
                "the profile update must not roll the cart back");
        assertEquals("Jane", storedUserName(userMail));
        assertEquals("34000", storedPostalCode(userMail));
    }

    /**
     * The reverse direction. A cart writer that read the row before the profile
     * was edited must not put the old profile back.
     */
    @Test
    void cartMutationOverlappingAProfileUpdatePreservesBothEffects() throws Exception {
        UserEntity user = createUser("cart-profile@migros.com");
        ProductEntity product = createProduct("ProfilePreserved", 10);
        String userMail = user.getUserMail();

        Future<Void> add;
        Future<Void> profile;
        try (Connection blocker = holdUserRowLock(user.getId())) {
            add = executor.submit(() -> {
                userCartService.addProductToCart(product.getId(), userMail);
                return null;
            });
            profile = executor.submit(() -> {
                uploadProfile(userMail);
                return null;
            });
            assertTrue(blocksOn(add));
            assertTrue(blocksOn(profile));
        }
        add.get(30, TimeUnit.SECONDS);
        profile.get(30, TimeUnit.SECONDS);

        assertEquals("Jane", storedUserName(userMail), "the cart write must not roll the profile back");
        assertEquals(List.of(product.getId()), storedCart(user.getId()));
    }

    /**
     * A password rotation and a ban are two more owners of the same row, and a
     * third is the profile. Whichever order they land in, none of them may undo
     * the others.
     *
     * <p>This one is deliberately <em>sequential</em>: it is a consistency check
     * that the three column-scoped writers can coexist, not an interleaving test.
     * The genuinely concurrent property - that a write cannot restore a snapshot
     * taken before another column's change committed - is covered by the
     * paused-read tests below, which fail against a whole-entity write.
     */
    @Test
    void passwordAndBanChangesSurviveAProfileUpdate() throws Exception {
        UserEntity user = createUser("password-ban@migros.com", "original-hash");
        String userMail = user.getUserMail();

        seedResetToken(userMail, "reset-1");
        userSignupService.resetPassword(resetDto("reset-1", STRONG_PASSWORD));
        supportModerationService.banUser(userMail);

        uploadProfile(userMail);

        assertEquals("Jane", storedUserName(userMail));
        assertFalse("original-hash".equals(storedPassword(userMail)),
                "a profile update must not restore the superseded password hash");
        assertTrue(storedPassword(userMail).startsWith("$2"));
        assertTrue(storedBanned(userMail), "a profile update must not clear the ban");

        // And the other way round: an unban and a second rotation after the edit.
        supportModerationService.unbanUser(userMail);
        seedResetToken(userMail, "reset-2");
        userSignupService.resetPassword(resetDto("reset-2", OTHER_STRONG_PASSWORD));

        assertEquals("Jane", storedUserName(userMail));
        assertTrue(storedPassword(userMail).startsWith("$2"));
        assertFalse(storedBanned(userMail));
    }

    /**
     * The ban reads the row to check the account exists and then writes the ban
     * flag. Its read is paused here, so a cart addition, a profile edit and a
     * password rotation all commit in between. A whole-entity write would
     * restore all three from the snapshot it read before they happened.
     */
    @Test
    void banDoesNotRestoreAStaleCartProfileOrPassword() throws Exception {
        UserEntity user = createUser("ban-stale@migros.com", "original-hash");
        ProductEntity product = createProduct("BanSurvivor", 10);
        String userMail = user.getUserMail();

        CountDownLatch banHasRead = new CountDownLatch(1);
        CountDownLatch letTheBanProceed = new CountDownLatch(1);
        pauseAfterFindByUserMail(userMail, banHasRead, letTheBanProceed);

        Future<Void> ban = executor.submit(() -> {
            supportModerationService.banUser(userMail);
            return null;
        });

        assertTrue(banHasRead.await(30, TimeUnit.SECONDS), "the ban never reached its read");
        try {
            userCartService.addProductToCart(product.getId(), userMail);
            uploadProfile(userMail);
            seedResetToken(userMail, "reset-3");
            userSignupService.resetPassword(resetDto("reset-3", STRONG_PASSWORD));
        } finally {
            letTheBanProceed.countDown();
        }
        ban.get(30, TimeUnit.SECONDS);

        assertEquals(List.of(product.getId()), storedCart(user.getId()),
                "the ban must not restore the cart it read before the addition");
        assertEquals("Jane", storedUserName(userMail),
                "the ban must not restore the profile it read before the edit");
        assertFalse("original-hash".equals(storedPassword(userMail)),
                "the ban must not restore the superseded password hash");
        assertTrue(storedBanned(userMail));
    }

    /** Unbanning is the same write with the other value, with the same guarantee. */
    @Test
    void unbanDoesNotRestoreAStaleCartProfileOrPassword() throws Exception {
        UserEntity user = createUser("unban-stale@migros.com", "original-hash");
        ProductEntity product = createProduct("UnbanSurvivor", 10);
        String userMail = user.getUserMail();
        supportModerationService.banUser(userMail);

        CountDownLatch unbanHasRead = new CountDownLatch(1);
        CountDownLatch letTheUnbanProceed = new CountDownLatch(1);
        pauseAfterFindByUserMail(userMail, unbanHasRead, letTheUnbanProceed);

        Future<Void> unban = executor.submit(() -> {
            supportModerationService.unbanUser(userMail);
            return null;
        });

        assertTrue(unbanHasRead.await(30, TimeUnit.SECONDS));
        try {
            userCartService.addProductToCart(product.getId(), userMail);
            uploadProfile(userMail);
        } finally {
            letTheUnbanProceed.countDown();
        }
        unban.get(30, TimeUnit.SECONDS);

        assertEquals(List.of(product.getId()), storedCart(user.getId()));
        assertEquals("Jane", storedUserName(userMail));
        assertFalse(storedBanned(userMail));
    }

    /**
     * Checkout preparation has to read the cart from the locked, freshly loaded
     * row. A locking query is not permission to trust an entity that was already
     * managed: it is handed back as-is, so a cart change committed after the
     * service's first read but before its lock would be invisible to the
     * reservation - the item would be neither charged nor reserved, and the final
     * cart write would erase it.
     *
     * <p>The competing addition commits while the preparation sits in front of
     * its locked load, which is exactly that window.
     */
    @Test
    void checkoutPreparationReadsFreshLockedStateAfterACompetingCartUpdate() throws Exception {
        UserEntity user = createUser("checkout-fresh@migros.com");
        ProductEntity alreadyInCart = createProduct("AlreadyInCart", 10);
        ProductEntity addedWhilePreparing = createProduct("AddedWhilePreparing", 10);
        user.setProductsIdsInCart(new ArrayList<>(List.of(alreadyInCart.getId())));
        userEntityRepository.saveAndFlush(user);
        String userToken = tokenService.generateUserToken(user.getUserMail());

        // The competing addition commits while the preparation sits in front of
        // its locked load, which is exactly that window. Only the worker thread
        // is parked: the test thread issues the competing addition itself.
        UserEntityRepository delegate = unspiedRepository();
        Thread testThread = Thread.currentThread();
        CountDownLatch prepareReachedTheLockedLoad = new CountDownLatch(1);
        CountDownLatch letThePreparationProceed = new CountDownLatch(1);
        doAnswer(invocation -> {
            String requestedMail = invocation.getArgument(0);
            if (!Thread.currentThread().equals(testThread) && user.getUserMail().equals(requestedMail)) {
                prepareReachedTheLockedLoad.countDown();
                assertTrue(letThePreparationProceed.await(30, TimeUnit.SECONDS),
                        "the test never released the preparation");
            }
            return delegate.findByUserMailForUpdate(requestedMail);
        }).when(userEntityRepository).findByUserMailForUpdate(anyString());

        Future<CheckoutResponseDto> prepare =
                executor.submit(() -> checkoutService.prepareCheckout(userToken));

        assertTrue(prepareReachedTheLockedLoad.await(30, TimeUnit.SECONDS),
                "the preparation never reached its locked user load");
        userCartService.addProductToCart(addedWhilePreparing.getId(), user.getUserMail());
        letThePreparationProceed.countDown();

        CheckoutResponseDto prepared = prepare.get(30, TimeUnit.SECONDS);

        List<CheckoutEntity> checkouts = checkoutEntityRepository.findAll();
        assertEquals(1, checkouts.size(), "one preparation must produce exactly one reservation");
        List<CheckoutItemEntity> items = checkoutItemEntityRepository
                .findByCheckout_IdOrderByProductIdAsc(checkouts.get(0).getId());
        assertEquals(2, items.size(),
                "the item added while the preparation waited must be reserved too, not lost");
        List<Long> reservedProductIds = items.stream().map(CheckoutItemEntity::getProductId).toList();
        assertTrue(reservedProductIds.contains(alreadyInCart.getId()));
        assertTrue(reservedProductIds.contains(addedWhilePreparing.getId()));

        assertEquals(0, new BigDecimal("20.00").compareTo(prepared.totalAmount()),
                "the total must cover both reserved units, not just the ones read before the lock");
        assertEquals(2000L, prepared.amountMinor());
        assertEquals(9, productEntityRepository.findById(alreadyInCart.getId()).orElseThrow().getProductCount());
        assertEquals(9, productEntityRepository.findById(addedWhilePreparing.getId()).orElseThrow().getProductCount());
        assertEquals(List.of(), storedCart(user.getId()), "preparing empties the cart exactly once");
    }

    /**
     * Lets the first {@code findByUserMail} that a <em>worker</em> thread issues
     * for {@code userMail} return its real result and then parks that thread, so
     * the caller holds a snapshot of the row while the test mutates it. This is
     * the read-then-write split a row lock alone cannot stage, and it is the
     * window in which a whole-entity save does its damage.
     *
     * <p>The answer delegates to the spied repository rather than to
     * {@code callRealMethod()}: a Spring Data repository is a JDK proxy, and
     * Mockito cannot dispatch a "real method" for it. It also must not delegate
     * to the spy itself, which would re-enter this stub and recurse forever.
     */
    private void pauseAfterFindByUserMail(String userMail, CountDownLatch read, CountDownLatch proceed) {
        UserEntityRepository delegate = unspiedRepository();
        Thread testThread = Thread.currentThread();
        doAnswer(invocation -> {
            UserEntity found = delegate.findByUserMail(invocation.getArgument(0));
            if (!Thread.currentThread().equals(testThread) && userMail.equals(invocation.getArgument(0))) {
                read.countDown();
                assertTrue(proceed.await(30, TimeUnit.SECONDS), "the test never released the parked thread");
            }
            return found;
        }).when(userEntityRepository).findByUserMail(anyString());
    }

    /**
     * A second, un-spied repository over the same {@code EntityManager}, so a
     * stubbed answer can produce the real result. Delegating to the spy itself
     * would re-enter the stub and recurse forever, and Mockito cannot dispatch a
     * "real method" for a Spring Data repository, which is a JDK proxy.
     */
    private UserEntityRepository unspiedRepository() {
        return new JpaRepositoryFactory(entityManager).getRepository(UserEntityRepository.class);
    }

    private void uploadProfile(String userMail) {
        userProfileService.uploadUserProfileTable(
                "Jane", "Smith", "1 Main St", "Apt 4", "Istanbul", "Turkey", "34000", userMail);
    }

    private void seedResetToken(String userMail, String token) {
        pendingSignupEntityRepository.saveAndFlush(new PendingSignupEntity(
                token, userMail, "hash", LocalDateTime.now().plusMinutes(15), PendingTokenPurpose.PASSWORD_RESET));
    }

    private ResetPasswordDto resetDto(String token, String password) {
        ResetPasswordDto dto = new ResetPasswordDto();
        dto.setToken(token);
        dto.setUserPassword(password);
        return dto;
    }

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

    private String storedUserName(String userMail) {
        return jdbcTemplate.queryForObject(
                "SELECT user_name FROM user_entity WHERE user_mail = ?", String.class, userMail);
    }

    private String storedPostalCode(String userMail) {
        return jdbcTemplate.queryForObject(
                "SELECT user_postal_code FROM user_entity WHERE user_mail = ?", String.class, userMail);
    }

    private String storedPassword(String userMail) {
        return jdbcTemplate.queryForObject(
                "SELECT user_password FROM user_entity WHERE user_mail = ?", String.class, userMail);
    }

    private boolean storedBanned(String userMail) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT banned FROM user_entity WHERE user_mail = ?", Boolean.class, userMail));
    }

    private UserEntity createUser(String mail) {
        return createUser(mail, null);
    }

    private UserEntity createUser(String mail, String password) {
        UserEntity user = new UserEntity();
        user.setUserMail(mail);
        user.setUserName("Tester");
        user.setUserPassword(password);
        user.setBanned(false);
        user.setProductsIdsInCart(new ArrayList<>());
        return userEntityRepository.saveAndFlush(user);
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
