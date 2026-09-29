package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class UserCartConcurrencyPostgresTest {

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
    private TokenService tokenService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        String token = tokenService.generateUserToken(user.getUserMail());

        CountDownLatch start = new CountDownLatch(1);
        Future<Void> firstAdd = executor.submit(() -> {
            start.await();
            userCartService.addProductToCart(first.getId(), token);
            return null;
        });
        Future<Void> secondAdd = executor.submit(() -> {
            start.await();
            userCartService.addProductToCart(second.getId(), token);
            return null;
        });
        start.countDown();

        firstAdd.get(30, TimeUnit.SECONDS);
        secondAdd.get(30, TimeUnit.SECONDS);

        List<Long> cart = userEntityRepository.findById(user.getId())
                .orElseThrow()
                .getProductsIdsInCart();
        assertNotNull(cart);
        assertEquals(2, cart.size(),
                "both concurrent additions must survive; a lost update drops one");
        assertTrue(cart.contains(first.getId()));
        assertTrue(cart.contains(second.getId()));
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
        ProductEntity product = new ProductEntity();
        product.setProductName(name);
        product.setSubcategoryName("general");
        product.setProductCount(5);
        product.setProductPrice(new BigDecimal("10.00"));
        product.setProductDiscount(BigDecimal.ZERO);
        product.setProductDescription("test product");
        return productEntityRepository.saveAndFlush(product);
    }
}
