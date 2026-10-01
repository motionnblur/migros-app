package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.dto.admin.panel.AdminAddItemDto;
import com.example.MigrosBackend.dto.user.product.ProductDetailDto;
import com.example.MigrosBackend.dto.user.product.ProductDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.dto.user.product.ProductSearchResponseDto;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.exception.admin.ProductEditConflictException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.helper.ProductUnitPricePolicy;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.service.user.supply.ProductSearchService;
import com.example.MigrosBackend.service.user.supply.UserSupplyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Package metadata through all three write paths and back out through every
 * customer read.
 *
 * <p>Run against real PostgreSQL rather than mocked repositories, because the
 * claims being made here are claims about what the schema accepts and what a
 * listing returns, and a mock cannot establish either. In particular: a unit
 * price is only correct if it was computed from the price the card shows, and the
 * only way to see that is to store a discounted product and read it back through
 * the same projection the customer uses.
 *
 * <p>All three write paths are covered here even though two of them differ only in
 * transport, because that difference is exactly what used to let the paths drift:
 * the multipart form once validated nothing the JSON body did, and the edit path
 * once bypassed the policy entirely. A rule that only one path enforces is a rule
 * the customer will meet on the other two.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class ProductPackageMetadataPostgresTest {

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
    }

    @Autowired
    private AdminSupplyService adminSupplyService;
    @Autowired
    private UserSupplyService userSupplyService;
    @Autowired
    private ProductSearchService productSearchService;
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
        jdbcTemplate.execute("TRUNCATE TABLE product_image_entity, product_entity, "
                + "admin_entity, category_entity RESTART IDENTITY CASCADE");
        deleteUploads();

        admin = new AdminEntity();
        admin.setAdminName("package-admin");
        admin.setItemEntities(new java.util.ArrayList<>());
        admin = adminEntityRepository.saveAndFlush(admin);

        category = new CategoryEntity();
        category.setCategoryId(21);
        category.setCategoryName("Dairy");
        category = categoryEntityRepository.saveAndFlush(category);
    }

    // -------------------------------------------------------------------------
    // Path 1: JSON creation
    // -------------------------------------------------------------------------

    @Test
    void aJsonCreatedProductStoresAndReportsItsPackageSize() {
        Long productId = createProduct("Süt", "Günlük", "24.99", 12, "0.00", "Fresh",
                "1.5", "L");

        assertEquals(0, new BigDecimal("1.500").compareTo(storedAmount(productId)));
        assertEquals("L", storedUnit(productId));

        ProductPreviewDto preview = previewOf(productId);
        assertEquals(0, new BigDecimal("1.5").compareTo(preview.getPackageAmount()));
        assertEquals("L", preview.getPackageUnit());
        assertEquals(0, new BigDecimal("16.66").compareTo(preview.getUnitPrice()),
                "24.99 TL for 1.5 L is 16.66 TL per litre");
        assertEquals("L", preview.getUnitPriceBasis());
    }

    /**
     * A product created without package metadata has none, and the reads say so
     * rather than inventing a size. This is the state of every row that predates
     * the feature, so it is the case that decides whether the listing can be
     * rendered at all.
     */
    @Test
    void aJsonCreatedProductWithoutMetadataReportsNoSizeAndNoUnitPrice() {
        Long productId = createProduct("Süt", "Günlük", "24.99", 12, "0.00", "Fresh", null, null);

        assertNull(storedAmount(productId));
        assertNull(storedUnit(productId));

        ProductPreviewDto preview = previewOf(productId);
        assertNull(preview.getPackageAmount());
        assertNull(preview.getPackageUnit());
        assertNull(preview.getUnitPrice());
        assertNull(preview.getUnitPriceBasis(),
                "a unit price with no basis is a second package price, and must not be sent");
    }

    /**
     * The unit price is a ratio of two numbers the customer is also looking at, so
     * a discount has to be inside the divisor's numerator.
     */
    @Test
    void theUnitPriceIsComputedFromTheDiscountedPackagePrice() {
        Long productId = createProduct("Zeytinyağı", "Sıvı", "100.00", 6, "20.00", "Olive",
                "1", "L");

        ProductDetailDto detail = adminSupplyService.getProductData(productId);
        assertEquals(0, new BigDecimal("100.00").compareTo(detail.getProductPrice()),
                "precondition: the stored price is the pre-discount one");
        assertEquals(0, new BigDecimal("80.00").compareTo(detail.getUnitPrice()),
                "a unit price built from the pre-discount price would advertise a saving the "
                        + "customer does not get");
        assertEquals("L", detail.getUnitPriceBasis());
    }

    /**
     * Grams and kilograms describe the same package and must price identically, at
     * three decimals of precision.
     */
    @Test
    void aStoredAmountInGramsAndInKilogramsPriceTheSame() {
        Long inGrams = createProduct("Peynir", "Köy", "45.00", 4, "0.00", "Cheese",
                "500", "G");
        Long inKilograms = createProduct("Peynir", "Köy", "45.00", 4, "0.00", "Cheese",
                "0.5", "KG");
        assertEquals(0, previewOf(inGrams).getUnitPrice()
                .compareTo(previewOf(inKilograms).getUnitPrice()),
                "500 G and 0.5 KG are the same package, so a factor of a thousand between them "
                        + "would make one of the two products look a hundred times dearer");
        assertEquals(0, new BigDecimal("90.00").compareTo(previewOf(inGrams).getUnitPrice()));
        assertEquals("KG", previewOf(inGrams).getUnitPriceBasis(),
                "the basis names the measure customers compare in, which is not always the "
                        + "measure the package is sold in");
    }

    // -------------------------------------------------------------------------
    // Path 2: multipart creation
    // -------------------------------------------------------------------------

    @Test
    void aMultipartCreatedProductStoresAndReportsItsPackageSize() throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), "Süt", "Günlük",
                new BigDecimal("24.99"), 12, BigDecimal.ZERO, "Fresh",
                category.getCategoryId(), new BigDecimal("0.75"), "KG", png());

        Long productId = newestProductId();
        assertEquals(0, new BigDecimal("0.750").compareTo(storedAmount(productId)));
        assertEquals("KG", storedUnit(productId));

        ProductDetailDto detail = adminSupplyService.getProductData(productId);
        assertEquals(0, new BigDecimal("33.32").compareTo(detail.getUnitPrice()));
        assertEquals("KG", detail.getUnitPriceBasis());
    }

    @Test
    void theMultipartPathAcceptsAMissingPackagePair() throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), "Süt", "Günlük",
                new BigDecimal("24.99"), 12, BigDecimal.ZERO, "Fresh",
                category.getCategoryId(), null, null, png());

        Long productId = newestProductId();
        assertNull(storedAmount(productId));
        assertNull(storedUnit(productId));
        assertNull(adminSupplyService.getProductData(productId).getUnitPrice());
    }

    /**
     * A multipart form field the administrator never touched binds to an empty
     * string, which has to mean "no unit" rather than "an unsupported unit". If it
     * did not, every product created through the admin form would be rejected.
     */
    @Test
    void theMultipartPathTreatsABlankUnitAsNoUnit() throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), "Süt", "Günlük",
                new BigDecimal("24.99"), 12, BigDecimal.ZERO, "Fresh",
                category.getCategoryId(), null, "   ", png());

        assertNull(storedUnit(newestProductId()));
    }

    /**
     * The same values through the same rules must produce the same stored product,
     * whichever path was used - which is the only thing that makes "the form" and
     * "the API" two names for one thing rather than two products.
     */
    @Test
    void equivalentJsonAndMultipartRequestsStoreTheSamePackageMetadata() throws IOException {
        Long jsonProductId = createProduct("Bal", "Cay", "300.00", 2, "5.00", "Honey",
                "500", "G");
        adminSupplyService.uploadProduct(admin.getId(), "Bal", "Cay",
                new BigDecimal("300.00"), 2, new BigDecimal("5.00"), "Honey",
                category.getCategoryId(), new BigDecimal("500"), "G", png());
        Long uploadProductId = newestProductId();

        assertEquals(storedAmount(jsonProductId), storedAmount(uploadProductId));
        assertEquals(storedUnit(jsonProductId), storedUnit(uploadProductId));
        assertEquals(0, previewOf(jsonProductId).getUnitPrice()
                .compareTo(previewOf(uploadProductId).getUnitPrice()));
    }

    // -------------------------------------------------------------------------
    // Rejections, which must leave nothing behind on every path
    // -------------------------------------------------------------------------

    /**
     * A half-filled pair is refused on every write path, and refused before
     * anything is written - including before the upload directory is touched,
     * since the file is the one part of an upload a rollback cannot undo.
     */
    @Test
    void aHalfFilledPairIsRefusedOnEveryPathAndLeavesNoProductAndNoFile() throws IOException {
        assertThrows(GeneralException.class, () -> createProduct(
                "Süt", "Günlük", "24.99", 12, "0.00", "Fresh", "500", null));
        assertEquals(0, productEntityRepository.count());
        assertEquals(0, storedFiles().count());

        assertThrows(GeneralException.class, () -> createProduct(
                "Süt", "Günlük", "24.99", 12, "0.00", "Fresh", null, "KG"));
        assertEquals(0, productEntityRepository.count());

        assertThrows(GeneralException.class, () -> adminSupplyService.uploadProduct(
                admin.getId(), "Süt", "Günlük", new BigDecimal("24.99"), 12, BigDecimal.ZERO,
                "Fresh", category.getCategoryId(), new BigDecimal("500"), null, png()));
        assertEquals(0, productEntityRepository.count());
        assertEquals(0, storedFiles().count(),
                "the file is the one thing a rollback cannot undo, so it must not be written at all");
    }

    /**
     * An unsupported unit is a 400 the administrator can act on, and it names the
     * options - a refusal that says "invalid value" leaves them guessing.
     */
    @Test
    void anUnsupportedUnitIsRefusedWithTheAcceptedList() {
        GeneralException exception = assertThrows(GeneralException.class, () -> createProduct(
                "Süt", "Günlük", "24.99", 12, "0.00", "Fresh", "500", "GRAM"));

        assertEquals("Package unit must be one of [G, KG, ML, L, ADET]", exception.getMessage());
        assertEquals(0, productEntityRepository.count());
    }

    /**
     * The amount bounds, on the create path so the row never has to be rolled back
     * to find out.
     */
    @Test
    void anAmountOutsideTheColumnIsRefusedAndLeavesNoProduct() {
        assertThrows(GeneralException.class, () -> createProduct(
                "Süt", "Günlük", "24.99", 12, "0.00", "Fresh", "0", "KG"));
        assertEquals(0, productEntityRepository.count());

        assertThrows(GeneralException.class, () -> createProduct(
                "Süt", "Günlük", "24.99", 12, "0.00", "Fresh", "-1", "KG"));
        assertEquals(0, productEntityRepository.count());

        assertThrows(GeneralException.class, () -> createProduct(
                "Süt", "Günlük", "24.99", 12, "0.00", "Fresh", "0.0001", "KG"));
        assertEquals(0, productEntityRepository.count());

        assertThrows(GeneralException.class, () -> createProduct(
                "Süt", "Günlük", "24.99", 12, "0.00", "Fresh", "1000000000", "KG"));
        assertEquals(0, productEntityRepository.count());

        assertThrows(GeneralException.class, () -> createProduct(
                "Süt", "Günlük", "24.99", 12, "0.00", "Fresh", "1.5", "ADET"));
        assertEquals(0, productEntityRepository.count());
    }

    /**
     * A rejected multipart request leaves no file on disk, whatever it carried in
     * the package fields.
     */
    @Test
    void aRejectedCreationLeavesNoOrphanFile() throws IOException {
        assertThrows(GeneralException.class, () -> adminSupplyService.uploadProduct(
                admin.getId(), "Süt", "Günlük", new BigDecimal("24.99"), 12, BigDecimal.ZERO,
                "Fresh", category.getCategoryId(), new BigDecimal("1.5"), "ADET", png()));

        assertEquals(0, storedFiles().count());
        assertEquals(0, productEntityRepository.count());
    }

    // -------------------------------------------------------------------------
    // Path 3: the version-checked edit
    // -------------------------------------------------------------------------

    /**
     * A metadata-only edit is an ordinary version-checked edit: it advances the
     * version, which is the only way another open form learns the product changed.
     *
     * <p>This is the case that would be missed by a test that only changes the
     * price. A size is stored on the product row, so an edit that moves it dirties
     * that row; if the version did not move, a second form holding the previous
     * size would still be believed current and could write it straight back.
     */
    @Test
    void aMetadataOnlyEditAdvancesTheVersion() {
        Long productId = createProduct("Süt", "Günlük", "24.99", 12, "0.00", "Fresh", null, null);
        long versionBefore = versionOf(productId);

        long versionAfter = adminSupplyService.updateProduct(admin.getId(), productId,
                "Süt", "Günlük", new BigDecimal("24.99"), 12, BigDecimal.ZERO, "Fresh",
                category.getCategoryId(), new BigDecimal("1"), "L", null, versionBefore);

        assertEquals(versionBefore + 1, versionAfter);
        assertEquals(versionBefore + 1, versionOf(productId),
                "the version is read off the row after the flush, so it is the one the row carries");
        assertEquals(0, new BigDecimal("1.000").compareTo(storedAmount(productId)));
        assertEquals("L", storedUnit(productId));
        assertEquals(0, new BigDecimal("24.99").compareTo(previewOf(productId).getUnitPrice()));
    }

    /**
     * Clearing the fields is how metadata is removed, and the pair is written on
     * every save rather than only when set - so an edit that omits them has to
     * leave the row without them.
     */
    @Test
    void anEditClearsPackageMetadataWhenBothFieldsAreEmpty() {
        Long productId = createProduct("Süt", "Günlük", "24.99", 12, "0.00", "Fresh",
                "1", "L");

        adminSupplyService.updateProduct(admin.getId(), productId, "Süt", "Günlük",
                new BigDecimal("24.99"), 12, BigDecimal.ZERO, "Fresh",
                category.getCategoryId(), null, null, null, versionOf(productId));

        assertNull(storedAmount(productId));
        assertNull(storedUnit(productId));
        assertNull(previewOf(productId).getUnitPrice(),
                "a cleared size must not leave a unit price behind");
    }

    /**
     * A stale editor is refused, and the refusal mutates nothing - including the
     * size it tried to write.
     *
     * <p>The size is the interesting field here: an editor holding a stale form is
     * exactly the one that would otherwise put an old package size back and make
     * the unit price wrong for every customer who reads it afterwards.
     */
    @Test
    void aRejectedStaleEditMutatesNothingIncludingThePackageSize() throws IOException {
        Long productId = createProduct("Süt", "Günlük", "24.99", 12, "0.00", "Fresh",
                "1", "L");
        long versionBefore = versionOf(productId);
        long filesBefore = storedFiles().count();

        // Another writer moves the row on, as a checkout reservation would.
        jdbcTemplate.update("UPDATE product_entity SET version = version + 1 "
                + "WHERE product_entity_id = ?", productId);

        assertThrows(ProductEditConflictException.class,
                () -> adminSupplyService.updateProduct(admin.getId(), productId,
                        "Süt", "Günlük", new BigDecimal("99.00"), 999, BigDecimal.ZERO, "Fresh",
                        category.getCategoryId(), new BigDecimal("9"), "KG", png(), versionBefore));

        assertEquals(0, new BigDecimal("1.000").compareTo(storedAmount(productId)),
                "the rejected edit's package size must not have been written");
        assertEquals("L", storedUnit(productId));
        assertEquals(12, jdbcTemplate.queryForObject(
                "SELECT product_count FROM product_entity WHERE product_entity_id = ?",
                Integer.class, productId));
        assertEquals(filesBefore, storedFiles().count(),
                "a rejected edit must not write the upload it carries");
    }

    /**
     * An invalid pair is refused before the version is even looked at, so an
     * editor who typed nonsense is told what is wrong with the form rather than
     * being told the product moved.
     */
    @Test
    void anInvalidEditIsRefusedBeforeAnyLookup() {
        Long productId = createProduct("Süt", "Günlük", "24.99", 12, "0.00", "Fresh",
                "1", "L");
        long versionBefore = versionOf(productId);

        assertThrows(GeneralException.class,
                () -> adminSupplyService.updateProduct(admin.getId(), productId,
                        "Süt", "Günlük", new BigDecimal("24.99"), 12, BigDecimal.ZERO, "Fresh",
                        category.getCategoryId(), new BigDecimal("1.5"), "ADET", null, versionBefore));

        assertEquals(versionBefore, versionOf(productId));
        assertEquals("L", storedUnit(productId));
    }

    // -------------------------------------------------------------------------
    // Every customer read agrees
    // -------------------------------------------------------------------------

    /**
     * The category listing, the search endpoint and the detail read are three
     * separate reads, and a product must not look different depending on which one
     * the customer happened to arrive through.
     *
     * <p>This is asserted on the values rather than on the code path, because the
     * failure mode is a listing that quietly omits the package size: the product
     * still renders, it just cannot be compared.
     */
    @Test
    void everyCustomerReadProjectsTheSamePackageMetadata() {
        Long productId = createProduct("Süt", "Günlük", "24.99", 12, "0.00", "Fresh",
                "0.75", "KG");

        ProductPreviewDto fromCategory = userSupplyService
                .getProductsFromCategory(category.getId(), 0, 10).get(0);
        assertEquals(productId, fromCategory.getProductId());
        assertEquals(0, new BigDecimal("0.75").compareTo(fromCategory.getPackageAmount()));
        assertEquals("KG", fromCategory.getPackageUnit());
        assertEquals(0, new BigDecimal("33.32").compareTo(fromCategory.getUnitPrice()));
        assertEquals("KG", fromCategory.getUnitPriceBasis());

        ProductPreviewDto fromSubcategory =
                userSupplyService.getProductsFromSubcategory("Günlük", 0, 10).get(0);
        assertEquals(0, new BigDecimal("33.32").compareTo(fromSubcategory.getUnitPrice()));
        assertEquals(fromCategory.getUnitPrice(), fromSubcategory.getUnitPrice());

        ProductPreviewDto fromAll = userSupplyService.getAllProducts(0, 10).get(0);
        assertEquals(fromCategory.getUnitPrice(), fromAll.getUnitPrice());

        ProductDetailDto detail = adminSupplyService.getProductData(productId);
        assertEquals(0, fromCategory.getUnitPrice().compareTo(detail.getUnitPrice()));
        assertEquals("KG", detail.getUnitPriceBasis());

        // The admin editor's own source, which has to carry the stored size so the
        // form can be prefilled rather than guessed at.
        assertEquals(0, new BigDecimal("0.750").compareTo(detail.getPackageAmount()));
        assertEquals("KG", detail.getPackageUnit());
        assertNotNull(detail.getProductVersion());
    }

    /**
     * The search endpoint is a separate read with its own query, and it renders the
     * same projection. The filter, count and sort semantics are not what this test
     * is about - it exists so a new field cannot appear on one listing and not the
     * other.
     */
    @Test
    void theSearchEndpointProjectsTheSamePackageMetadata() {
        createProduct("Süt", "Günlük", "24.99", 12, "0.00", "Fresh", "0.75", "KG");

        ProductSearchResponseDto response = productSearchService.search(null, category.getId(),
                null, null, null, null, null, null, 0, 10);

        assertEquals(1, response.items().size());
        ProductPreviewDto item = response.items().get(0);
        assertEquals(0, new BigDecimal("0.75").compareTo(item.getPackageAmount()));
        assertEquals("KG", item.getPackageUnit());
        assertEquals(0, new BigDecimal("33.32").compareTo(item.getUnitPrice()));
        assertEquals("KG", item.getUnitPriceBasis());
    }

    /**
     * A product created before package metadata existed still lists, still shows
     * its price, and simply has no package line. This is the case for almost every
     * row in a real database, and the reason the fields are nullable rather than
     * defaulted.
     */
    @Test
    void aProductWithoutPackageMetadataStillListsAndShowsNoUnitPrice() {
        Long productId = createProduct("Süt", "Günlük", "24.99", 12, "0.00", "Fresh", null, null);

        List<ProductPreviewDto> listings = userSupplyService
                .getProductsFromCategory(category.getId(), 0, 10);
        assertEquals(1, listings.size());
        assertEquals(productId, listings.get(0).getProductId());
        assertEquals(0, new BigDecimal("24.99").compareTo(listings.get(0).getProductPrice()),
                "the price the card shows is unaffected by the absence of a package size");
        assertNull(listings.get(0).getUnitPrice());
    }

    /**
     * A product the schema could hold but the policy refuses - a size with a unit
     * outside the closed set - keeps its size and reports no unit price. The size
     * is a fact; the divisor is not derivable.
     */
    @Test
    void aSizeWithAnUnrecognizedUnitKeepsItsSizeAndReportsNoUnitPrice() {
        Long productId = createProduct("Süt", "Günlük", "24.99", 12, "0.00", "Fresh", null, null);
        jdbcTemplate.update("UPDATE product_entity SET package_amount = 500.000, "
                + "package_unit = 'GRAM' WHERE product_entity_id = ?", productId);

        ProductPreviewDto preview = previewOf(productId);

        assertEquals(0, new BigDecimal("500.000").compareTo(preview.getPackageAmount()));
        assertEquals("GRAM", preview.getPackageUnit());
        assertNull(preview.getUnitPrice());
        assertNull(preview.getUnitPriceBasis());
        assertNull(ProductUnitPricePolicy.unitPrice(
                new BigDecimal("24.99"), new BigDecimal("500"), "GRAM"));
    }

    /**
     * The pair is written in the same transaction as the product, so a rollback
     * cannot leave a product row with a package size nothing else knows about.
     */
    @Test
    void aRollbackRemovesTheProductAndItsPackageMetadata() {
        TransactionTemplate transaction = new TransactionTemplate(txManager);
        transaction.execute(status -> {
            adminSupplyService.addProduct(addItemDto("Süt", "Günlük", "24.99", 12, "0.00",
                    "Fresh", "Dairy", "1", "L"));
            status.setRollbackOnly();
            return null;
        });

        assertEquals(0, productEntityRepository.count());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates one product through the JSON path.
     *
     * <p>The category is fixed to the one this test seeds, because what these tests
     * are about is the package fields and a varying category would only add a
     * lookup to each assertion.
     */
private Long createProduct(String productName, String subCategoryName, String price,
                               int count, String discount, String description,
                               String amount, String unit) {
        adminSupplyService.addProduct(addItemDto(productName, subCategoryName, price, count,
                discount, description, "Dairy", amount, unit));
        return newestProductId();
    }

    /**
     * The product the most recent write produced.
     *
     * <p>Written this way rather than by looking up a name, because several of
     * these tests deliberately create two products with the <em>same</em> name -
     * one in grams and one in kilograms - and a name lookup could not tell them
     * apart.
     */
    private Long newestProductId() {
        return jdbcTemplate.queryForObject(
                "SELECT max(product_entity_id) FROM product_entity", Long.class);
    }

    /**
     * The customer preview, read through the search endpoint's repository query so
     * the row under test is the one whose projection is being asserted rather than
     * an accident of ordering.
     */
    private ProductPreviewDto previewOf(Long productId) {
        return userSupplyService.getAllProducts(0, 10).stream()
                .filter(preview -> productId.equals(preview.getProductId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no preview for product " + productId));
    }

    private BigDecimal storedAmount(Long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT package_amount FROM product_entity WHERE product_entity_id = ?",
                BigDecimal.class, productId);
    }

    private String storedUnit(Long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT package_unit FROM product_entity WHERE product_entity_id = ?",
                String.class, productId);
    }

    private long versionOf(Long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM product_entity WHERE product_entity_id = ?",
                Long.class, productId);
    }

    private AdminAddItemDto addItemDto(String productName, String subCategoryName, String price,
                                       int count, String discount, String description,
                                       String categoryName, String amount, String unit) {
        ProductDto productDto = new ProductDto();
        productDto.setProductName(productName);
        productDto.setSubCategoryName(subCategoryName);
        productDto.setProductPrice(new BigDecimal(price));
        productDto.setProductCount(count);
        productDto.setProductDiscount(discount == null ? null : new BigDecimal(discount));
        productDto.setProductDescription(description);
        productDto.setCategoryName(categoryName);
        productDto.setPackageAmount(amount == null ? null : new BigDecimal(amount));
        productDto.setPackageUnit(unit);

        AdminAddItemDto dto = new AdminAddItemDto();
        dto.setAdminId(admin.getId());
        dto.setProductDto(productDto);
        return dto;
    }

    private MockMultipartFile png() {
        return new MockMultipartFile("selectedImage", "image.png", "image/png",
                "image-bytes".getBytes());
    }

    private Stream<Path> storedFiles() throws IOException {
        try (Stream<Path> files = Files.list(UPLOAD_DIR)) {
            return files.toList().stream();
        }
    }

    private void deleteUploads() throws IOException {
        try (Stream<Path> files = Files.list(UPLOAD_DIR)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
        assertFalse(Files.list(UPLOAD_DIR).findAny().isPresent());
    }

    private static Path createUploadDir() {
        try {
            return Files.createTempDirectory("migros-package-metadata-test");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
