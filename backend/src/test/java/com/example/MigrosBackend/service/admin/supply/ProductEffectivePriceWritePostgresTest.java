package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.dto.admin.panel.AdminAddItemDto;
import com.example.MigrosBackend.dto.user.product.ProductDto;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.helper.ProductPricingPolicy;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adding a column to the product row can break the edit version in two ways, and
 * both are silent.
 *
 * <p>{@code effective_price} is written on every create and edit, on the same
 * {@code applyTo} call that writes the price and discount it is derived from. That
 * is the right place for it - it is the only funnel the three write paths share -
 * but it puts a new column on the no-op path, and the no-op path is where the
 * version rule lives:
 *
 * <ul>
 *   <li>A <b>genuine</b> edit must advance {@code @Version}. If the derived column
 *       were computed from something other than the two columns being written, an
 *       edit that changes only that something would have to dirty the row, and
 *       this is where that would be caught.</li>
 *   <li>A <b>no-op</b> edit must <em>not</em> advance it. Advancing it would
 *       invalidate every other administrator's open form over a change that did not
 *       happen, turning the guard into an obstacle. This is the risk the new column
 *       actually adds: it is recomputed on every edit, so if it were recomputed to
 *       a value that differs from what is stored even when nothing changed, every
 *       no-op would become a real edit.</li>
 * </ul>
 *
 * <p>The two boundaries are asserted here rather than inferred from
 * {@code ProductImageVersionAdvancePostgresTest}, which covers the image-only
 * case for its own reasons.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class ProductEffectivePriceWritePostgresTest {

    private static final String USER_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String ADMIN_SECRET = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    private static final Path UPLOAD_DIR = createUploadDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jwt.user-secret", () -> USER_SECRET);
        registry.add("jwt.admin-secret", () -> ADMIN_SECRET);
        registry.add("support.internal.key", () -> "integration-test-internal-key");
        registry.add("support.service.internal-key", () -> "integration-test-internal-key");
        registry.add("app.upload-dir", () -> UPLOAD_DIR.toString());
        registry.add("product-image.cleanup.scan-ms", () -> "3600000");
        registry.add("product-image.cleanup.initial-delay-ms", () -> "3600000");
        registry.add("product-image.cleanup.retention-scan-ms", () -> "3600000");
        registry.add("product-image.cleanup.retention-initial-delay-ms", () -> "3600000");
        registry.add("product-image.cleanup.backoff-base-seconds", () -> "1");
        registry.add("product-image.cleanup.backoff-max-seconds", () -> "1");
        registry.add("product-image.cleanup.reference-recheck-seconds", () -> "1");
    }

    @MockitoSpyBean
    private ProductImageCleanupStore cleanupStore;

    @Autowired
    private AdminSupplyService adminSupplyService;
    @Autowired
    private AdminEntityRepository adminEntityRepository;
    @Autowired
    private CategoryEntityRepository categoryEntityRepository;
    @Autowired
    private ProductEntityRepository productEntityRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager txManager;

    private AdminEntity admin;
    private CategoryEntity category;

    @BeforeEach
    void resetState() throws IOException {
        jdbcTemplate.execute("TRUNCATE TABLE product_image_cleanup_entity, checkout_item_entity, "
                + "checkout_entity, user_entity, product_image_entity, "
                + "product_entity, admin_entity, category_entity RESTART IDENTITY CASCADE");
        deleteUploads();

        admin = new AdminEntity();
        admin.setAdminName("price-admin");
        admin.setItemEntities(new java.util.ArrayList<>());
        admin = adminEntityRepository.saveAndFlush(admin);

        category = new CategoryEntity();
        category.setCategoryId(21);
        category.setCategoryName("price-category");
        category = categoryEntityRepository.saveAndFlush(category);
    }

    // -------------------------------------------------------------------------
    // Every writer sets the column
    // -------------------------------------------------------------------------

    /**
     * The JSON creation path. It is the one that used to copy four fields and
     * insert, so it is the path most likely to have been missed by a rule that
     * assumes creation goes through the policy.
     */
    @Test
    void theJsonCreationPathStoresTheEffectivePrice() {
        AdminAddItemDto request = new AdminAddItemDto();
        request.setAdminId(admin.getId());

        ProductDto product = new ProductDto();
        product.setProductName("Discounted From Json");
        product.setSubCategoryName("General");
        product.setProductCount(4);
        product.setProductPrice(new BigDecimal("20.00"));
        product.setProductDiscount(new BigDecimal("12.50"));
        product.setCategoryName("price-category");
        request.setProductDto(product);

        adminSupplyService.addProduct(request);

        assertEquals("17.50", effectivePriceOf("Discounted From Json"));
    }

    @Test
    void theMultipartUploadPathStoresTheEffectivePrice() {
        adminSupplyService.uploadProduct(admin.getId(), "Discounted Upload", "General",
                new BigDecimal("20.00"), 4, new BigDecimal("12.50"), "desc",
                category.getCategoryId(), null, null, png());

        assertEquals("17.50", effectivePriceOf("Discounted Upload"));
    }

    @Test
    void aProductWithNoDiscountStoresItsOwnPriceAsTheEffectivePrice() {
        adminSupplyService.uploadProduct(admin.getId(), "No Discount", "General",
                new BigDecimal("7.25"), 4, BigDecimal.ZERO, "desc", category.getCategoryId(), null, null, png());

        assertEquals("7.25", effectivePriceOf("No Discount"));
    }

    /**
     * An omitted discount means no discount, and the effective price has to follow
     * it rather than being left null - a null here is a row no price filter or
     * ordering can use.
     */
    @Test
    void anOmittedDiscountStillProducesAUsableEffectivePrice() {
        ProductDto product = new ProductDto();
        product.setProductName("No Discount Field");
        product.setSubCategoryName("General");
        product.setProductCount(1);
        product.setProductPrice(new BigDecimal("9.99"));
        product.setCategoryName("price-category");

        AdminAddItemDto request = new AdminAddItemDto();
        request.setAdminId(admin.getId());
        request.setProductDto(product);

        adminSupplyService.addProduct(request);

        assertEquals("9.99", effectivePriceOf("No Discount Field"));
    }

    // -------------------------------------------------------------------------
    // The version rule, with the new column on the write path
    // -------------------------------------------------------------------------

    /**
     * A real edit rewrites the derived column and advances the version. If the
     * column only ever held the creation-time price, the search would keep ordering
     * by a price the shop no longer charges.
     */
    @Test
    void aRealEditRewritesTheEffectivePriceAndAdvancesTheVersion() throws IOException {
        Long productId = uploadWater();
        long versionBefore = versionOf(productId);

        adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                new BigDecimal("20.00"), 10, new BigDecimal("12.50"), "Fresh",
                category.getCategoryId(), null, null, null, versionBefore);

        assertEquals("17.50", effectivePriceOf("Water"), "the search must not keep ordering by 5.00");
        assertTrue(versionOf(productId) > versionBefore,
                "an edit that changes the price is a real edit and every open form for it is stale");

        // A discount-only edit moves the effective price without touching the price,
        // which is exactly the case a formula keyed off product_price alone misses.
        long versionAfterPriceEdit = versionOf(productId);
        adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                new BigDecimal("20.00"), 10, new BigDecimal("50.00"), "Fresh",
                category.getCategoryId(), null, null, null, versionAfterPriceEdit);

        assertEquals("10.00", effectivePriceOf("Water"));
        assertTrue(versionOf(productId) > versionAfterPriceEdit);
    }

    /**
     * The boundary the new column actually puts at risk.
     *
     * <p>{@code effective_price} is recomputed on every edit, so if it were recomputed
     * to a value that differed from the stored one even when nothing changed, every
     * no-op would become a real edit and this rule would be gone.
     */
    @Test
    void aGenuineNoOpLeavesBothTheEffectivePriceAndTheVersionUnmoved() throws IOException {
        Long productId = uploadWater();
        long versionAfterCreation = versionOf(productId);
        String effectiveAfterCreation = effectivePriceOf("Water");

        long returned = adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, null, versionAfterCreation);

        assertEquals(versionAfterCreation, returned, "a submission that changes nothing reports the "
                + "version it found");
        assertEquals(versionAfterCreation, versionOf(productId),
                "no change at all implies the version does not move");
        assertEquals(effectiveAfterCreation, effectivePriceOf("Water"),
                "and the derived column must be byte-identical, or every no-op would be a real edit");
    }

    /**
     * Stable, not merely absent: a second identical no-op submitted against the
     * version the first one left behind also has to succeed, which is only true if
     * the first one really did leave the version where it found it.
     */
    @Test
    void repeatingANoOpStaysSuccessfulAndStillDoesNotDriftTheVersion() throws IOException {
        Long productId = uploadWater();
        long versionAfterCreation = versionOf(productId);

        long first = adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, null, versionAfterCreation);
        long second = adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, null, first);

        assertEquals(versionAfterCreation, first);
        assertEquals(versionAfterCreation, second);
        assertEquals(versionAfterCreation, versionOf(productId));
    }

    /**
     * An image-only edit changes nothing on the product row except the version,
     * which the service forces explicitly. The derived column has to stay put: it
     * is computed from columns that did not change.
     */
    @Test
    void anImageOnlyEditAdvancesTheVersionAndLeavesTheEffectivePriceAlone() throws IOException {
        Long productId = uploadWater();
        long versionBefore = versionOf(productId);
        String effectiveBefore = effectivePriceOf("Water");

        long returned = adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png(), versionBefore);

        assertTrue(versionOf(productId) > versionBefore,
                "the image is a change to the product even though it is not a column on the row");
        assertEquals(versionOf(productId), returned);
        assertEquals(effectiveBefore, effectivePriceOf("Water"));
    }

    /**
     * The derived column is part of the row now, so a rollback has to take it with
     * the price it was derived from. A surviving row pairing the old price with the
     * new effective price would be a row the search sorts and displays inconsistently.
     */
    @Test
    void aRolledBackEditRestoresTheEffectivePriceAndTheVersionTogether() throws IOException {
        Long productId = uploadWater();
        long versionBefore = versionOf(productId);
        String effectiveBefore = effectivePriceOf("Water");

        TransactionTemplate transaction = new TransactionTemplate(txManager);
        transaction.execute(status -> {
            adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                    new BigDecimal("99.00"), 10, new BigDecimal("50.00"), "Fresh",
                    category.getCategoryId(), null, null, null, versionBefore);
            status.setRollbackOnly();
            return null;
        });

        assertEquals(versionBefore, versionOf(productId),
                "an undone edit must not leave the version advanced, or it would reject every open "
                        + "form over a change that never committed");
        assertEquals(effectiveBefore, effectivePriceOf("Water"));
        assertEquals("5.00", priceOf("Water"));
    }

    /**
     * A rejected edit changes nothing at all, including the derived column. The
     * version comparison runs before any mutation precisely so this holds.
     */
    @Test
    void aRejectedEditLeavesTheStoredEffectivePriceUntouched() throws IOException {
        Long productId = uploadWater();
        String effectiveBefore = effectivePriceOf("Water");

        // Another writer advanced the row while this editor's form was open.
        jdbcTemplate.update("UPDATE product_entity SET version = version + 1 WHERE product_entity_id = ?",
                productId);
        long staleVersion = versionOf(productId) - 1;

        assertThrows(RuntimeException.class, () -> adminSupplyService.updateProduct(admin.getId(), productId,
                "Rejected", "Rejected", new BigDecimal("99.00"), 999, new BigDecimal("50.00"), "Rejected",
                category.getCategoryId(), null, null, png(), staleVersion));

        assertEquals(effectiveBefore, effectivePriceOf("Water"));
    }

    /**
     * A stock movement is not a price movement. The bulk increment is the one
     * product writer that bypasses entity handling, so it is the one place a
     * derived column could be left inconsistent without anybody noticing.
     */
    @Test
    void aStockMovementDoesNotDisturbTheEffectivePrice() {
        Long productId = uploadWater();
        String effectiveBefore = effectivePriceOf("Water");

        jdbcTemplate.update("UPDATE product_entity SET product_count = product_count + 6 "
                + "WHERE product_entity_id = ?", productId);

        assertEquals(effectiveBefore, effectivePriceOf("Water"));
    }

    /**
     * The stored value is the policy's, checked through the entity mapping rather
     * than through raw SQL, so the mapping and the column are exercised together.
     */
    @Test
    void theStoredValueIsThePricingPolicyResultForTheStoredColumns() {
        adminSupplyService.uploadProduct(admin.getId(), "Boundary", "General",
                new BigDecimal("1.00"), 3, new BigDecimal("12.50"), "desc",
                category.getCategoryId(), null, null, png());

        assertEquals(0, productEntityRepository.findAll().get(0).getEffectivePrice()
                        .compareTo(ProductPricingPolicy.effectivePrice(
                                new BigDecimal("1.00"), new BigDecimal("12.50"))),
                "1.00 less 12.5% is 0.875, which rounds away from zero to 0.88");
    }

    /**
     * Validation still precedes the insert, so a price the policy could not price
     * is refused rather than stored with a derived value nobody can explain.
     */
    @Test
    void aPriceOutsideTheMoneyScaleIsStillRefused() {
        assertThrows(GeneralException.class,
                () -> adminSupplyService.uploadProduct(admin.getId(), "Too Precise", "General",
                        new BigDecimal("10.001"), 1, BigDecimal.ZERO, "desc",
                        category.getCategoryId(), null, null, png()));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Long uploadWater() {
        adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png());
        return productEntityRepository.findAll().get(0).getId();
    }

    private String effectivePriceOf(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT effective_price FROM product_entity WHERE product_name = ?",
                String.class, name);
    }

    private String priceOf(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT product_price FROM product_entity WHERE product_name = ?", String.class, name);
    }

    private long versionOf(Long productId) {
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM product_entity WHERE product_entity_id = ?", Long.class, productId);
        return version == null ? 0L : version;
    }

    private MockMultipartFile png() {
        return new MockMultipartFile("selectedImage", "image.png", "image/png",
                "image-bytes".getBytes());
    }

    private void deleteUploads() throws IOException {
        try (Stream<Path> files = Files.list(UPLOAD_DIR)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static Path createUploadDir() {
        try {
            return Files.createTempDirectory("migros-effective-price-test");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
