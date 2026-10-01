package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.dto.admin.panel.AdminAddItemDto;
import com.example.MigrosBackend.dto.admin.panel.AdminProductPreviewDto;
import com.example.MigrosBackend.dto.user.product.ProductDetailDto;
import com.example.MigrosBackend.dto.user.product.ProductDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.exception.admin.AdminNotFoundException;
import com.example.MigrosBackend.exception.admin.ProductEditConflictException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.product.ProductImageEntityRepository;
import com.example.MigrosBackend.service.user.supply.UserSupplyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * A product has to be a row the database actually accepts.
 *
 * <p>The JSON creation path was previously covered by a unit test whose mocked
 * {@code save} accepted whatever entity it was handed, so the entity it asserted
 * as a success - no description, no discount, no category - is one PostgreSQL
 * would refuse, since {@code product_description} and
 * {@code product_discount} are {@code NOT NULL} and the category key has no
 * default. A mock cannot establish any of that, so everything asserted here runs
 * against a real database: the constraints either hold or they do not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class ProductCreationPostgresTest {

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
        // These tests assert on how many files exist on disk. The cleanup
        // worker is scheduled and would eventually remove an obsolete one, so
        // any test here that replaces an image must drive it explicitly rather
        // than race it. Pinned off: the file counts below are the point.
        registry.add("product-image.cleanup.scan-ms", () -> "3600000");
        registry.add("product-image.cleanup.initial-delay-ms", () -> "3600000");
        registry.add("product-image.cleanup.retention-scan-ms", () -> "3600000");
        registry.add("product-image.cleanup.retention-initial-delay-ms", () -> "3600000");
    }

    @MockitoSpyBean
    private ProductEntityRepository productEntityRepository;

    @Autowired
    private AdminSupplyService adminSupplyService;
    @Autowired
    private UserSupplyService userSupplyService;
    @Autowired
    private AdminEntityRepository adminEntityRepository;
    @Autowired
    private CategoryEntityRepository categoryEntityRepository;
    @Autowired
    private ProductImageEntityRepository productImageEntityRepository;
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
        admin.setAdminName("creation-admin");
        admin.setItemEntities(new java.util.ArrayList<>());
        admin = adminEntityRepository.saveAndFlush(admin);

        category = new CategoryEntity();
        category.setCategoryId(11);
        category.setCategoryName("Beverages");
        category = categoryEntityRepository.saveAndFlush(category);
    }

    /**
     * The whole point of the change: a realistic JSON request is now a row.
     *
     * <p>Read back through SQL rather than through the entity, so the assertion
     * is about the stored columns and the stored foreign keys and not about a
     * persistence context that still holds the objects the service just built.
     */
    @Test
    void aValidJsonRequestPersistsACompleteRow() {
        Long productId = createProduct("Coke", "Cola", "1.50", 12, "0.10", "Iced cola", "Beverages");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT product_name, subcategory_name, product_count, product_price, "
                        + "product_discount, product_description, admin_entity_id, "
                        + "category_entity_id, version FROM product_entity WHERE product_entity_id = ?",
                productId);

        assertEquals("Coke", row.get("product_name"));
        assertEquals("Cola", row.get("subcategory_name"));
        assertEquals(12, row.get("product_count"));
        assertEquals(0, new BigDecimal("1.50").compareTo((BigDecimal) row.get("product_price")));
        assertEquals(0, new BigDecimal("0.10").compareTo((BigDecimal) row.get("product_discount")));
        assertEquals("Iced cola", row.get("product_description"),
                "product_description is NOT NULL: leaving it unset made this endpoint uninsertable");
        assertEquals(admin.getId(), row.get("admin_entity_id"));
        assertEquals(category.getId(), row.get("category_entity_id"),
                "a product with no category key cannot be listed under any category");
        assertEquals(0L, row.get("version"),
                "a new row takes its version from the column default, so the first edit can match it");
    }

    /**
     * An absent description is a product with no description, not a failed
     * insert. The multipart path has always stored the empty string; the two
     * paths now agree.
     */
    @Test
    void aJsonRequestWithoutADescriptionStoresTheEmptyString() {
        Long productId = createProduct("Coke", "Cola", "1.50", 12, null, null, "Beverages");

        assertEquals("", jdbcTemplate.queryForObject(
                "SELECT product_description FROM product_entity WHERE product_entity_id = ?",
                String.class, productId));
        assertEquals(0, BigDecimal.ZERO.compareTo(jdbcTemplate.queryForObject(
                "SELECT product_discount FROM product_entity WHERE product_entity_id = ?",
                BigDecimal.class, productId)));
    }

    /**
     * A product nothing can find is not a created product. Both listing paths and
     * the detail read go through the category, so a missing category link does not
     * fail the insert - it fails every read afterwards, which is much harder to
     * diagnose.
     */
    @Test
    void aJsonCreatedProductIsDiscoverableThroughTheExistingReads() {
        Long productId = createProduct("Coke", "Cola", "1.50", 12, "0.10", "Iced cola", "Beverages");

        List<AdminProductPreviewDto> adminListing = adminSupplyService.getAllAdminProducts(admin.getId(), 0, 10);
        assertEquals(1, adminListing.size(), "the admin who created it must be able to list it");
        assertEquals(productId, adminListing.get(0).getProductId());

        List<ProductPreviewDto> categoryListing =
                userSupplyService.getProductsFromCategory(category.getId(), 0, 10);
        assertEquals(1, categoryListing.size());
        assertEquals(productId, categoryListing.get(0).getProductId());

        List<ProductPreviewDto> subcategoryListing =
                userSupplyService.getProductsFromSubcategory("Cola", 0, 10);
        assertEquals(1, subcategoryListing.size());

        assertEquals(1, userSupplyService.getProductCountsFromCategory(category.getId()));
        assertEquals(1, userSupplyService.getSubCategories(category.getId()).size());

        ProductDetailDto detail = adminSupplyService.getProductData(productId);
        assertEquals("Coke", detail.getProductName());
        assertEquals("Cola", detail.getSubCategoryName());
        assertEquals("Iced cola", detail.getProductDescription());
        assertEquals(12, detail.getProductCount());
        assertEquals(Math.toIntExact(category.getId()), detail.getProductCategoryId());
        assertNotNull(detail.getProductVersion(),
                "the detail read is where an editor learns the version it has to submit");
    }

    /**
     * The reason the two creation endpoints were ever different is that they were
     * two sets of rules. Given the same values they now have to produce the same
     * stored product, so a client cannot get a different product by switching
     * between them.
     */
    @Test
    void equivalentJsonAndMultipartRequestsProduceTheSameStoredProduct() throws IOException {
        Long jsonProductId = createProduct("Water", "Still", "5.00", 10, "0.00", "Fresh", "Beverages");
        Long uploadProductId = uploadedProductId("Water", "Still", "5.00", 10, "0.00", "Fresh");

        Map<String, Object> fromJson = storedFieldsOf(jsonProductId);
        Map<String, Object> fromUpload = storedFieldsOf(uploadProductId);

        assertEquals(fromJson.get("product_name"), fromUpload.get("product_name"));
        assertEquals(fromJson.get("subcategory_name"), fromUpload.get("subcategory_name"));
        assertEquals(fromJson.get("product_count"), fromUpload.get("product_count"));
        assertEquals(fromJson.get("product_price"), fromUpload.get("product_price"));
        assertEquals(fromJson.get("product_discount"), fromUpload.get("product_discount"));
        assertEquals(fromJson.get("product_description"), fromUpload.get("product_description"));
        assertEquals(fromJson.get("category_entity_id"), fromUpload.get("category_entity_id"));
        assertEquals(fromJson.get("admin_entity_id"), fromUpload.get("admin_entity_id"));
        assertEquals(fromJson.get("version"), fromUpload.get("version"));
    }

    /**
     * A rejected request leaves nothing behind. The assertion is on the table,
     * not on a mocked repository having been or not having been called: only the
     * real constraint can say the row was never written.
     */
    @Test
    void aValueTheColumnCannotHoldLeavesNoProduct() {
        assertThrows(GeneralException.class, () -> createProduct(
                "x".repeat(256), "Cola", "1.50", 12, "0.10", "Iced cola", "Beverages"));
        assertEquals(0, productEntityRepository.count());

        assertThrows(GeneralException.class, () -> createProduct(
                "Coke", "Cola", "1.50", 12, "0.10", "x".repeat(256), "Beverages"));
        assertEquals(0, productEntityRepository.count());

        // NUMERIC(19, 2) holds seventeen integer digits.
        assertThrows(GeneralException.class, () -> createProduct(
                "Coke", "Cola", "100000000000000000.00", 12, "0.10", "Iced cola", "Beverages"));
        assertEquals(0, productEntityRepository.count());

        assertThrows(GeneralException.class, () -> createProduct(
                "Coke", "Cola", "1.505", 12, "0.10", "Iced cola", "Beverages"));
        assertEquals(0, productEntityRepository.count());

        assertThrows(GeneralException.class, () -> createProduct(
                "Coke", "Cola", "1.50", -1, "0.10", "Iced cola", "Beverages"));
        assertEquals(0, productEntityRepository.count());

        assertThrows(GeneralException.class, () -> createProduct(
                "Coke", "Cola", "1.50", 12, "100.01", "Iced cola", "Beverages"));
        assertEquals(0, productEntityRepository.count());

        assertThrows(GeneralException.class, () -> createProduct(
                "   ", "Cola", "1.50", 12, "0.10", "Iced cola", "Beverages"));
        assertEquals(0, productEntityRepository.count());
    }

    /**
     * A name is not a key in this schema, so it can match no row or several. Both
     * are refusals, and neither invents a category to file the product under.
     */
    @Test
    void anUnknownOrAmbiguousCategoryNameLeavesNoProduct() {
        assertThrows(GeneralException.class, () -> createProduct(
                "Coke", "Cola", "1.50", 12, "0.10", "Iced cola", "Nonexistent"));
        assertEquals(0, productEntityRepository.count());

        CategoryEntity duplicate = new CategoryEntity();
        duplicate.setCategoryId(12);
        duplicate.setCategoryName("Beverages");
        categoryEntityRepository.saveAndFlush(duplicate);

        assertThrows(GeneralException.class, () -> createProduct(
                "Coke", "Cola", "1.50", 12, "0.10", "Iced cola", "Beverages"));
        assertEquals(0, productEntityRepository.count());
    }

    @Test
    void anUnknownAdminLeavesNoProduct() {
        AdminAddItemDto dto = addItemDto("Coke", "Cola", "1.50", 12, "0.10", "Iced cola", "Beverages");
        dto.setAdminId(9999L);

        assertThrows(AdminNotFoundException.class, () -> adminSupplyService.addProduct(dto));

        assertEquals(0, productEntityRepository.count());
    }

    /**
     * Nothing was written, so nothing may be left on disk. The JSON path never
     * creates a file at all, and a rejected multipart request must not reach the
     * upload directory either.
     */
    @Test
    void aRejectedCreationLeavesNoOrphanFile() throws IOException {
        assertThrows(GeneralException.class, () -> createProduct(
                "Coke", "Cola", "1.50", 12, "0.10", "Iced cola", "Nonexistent"));
        assertEquals(0, storedFiles().count());

        assertThrows(GeneralException.class, () -> adminSupplyService.uploadProduct(
                admin.getId(), "Water", "Still", new BigDecimal("5.00"), 10, BigDecimal.ZERO,
                "Fresh", 987654, null, null, png()));
        assertEquals(0, storedFiles().count(),
                "the file is the one thing a rollback cannot undo, so it must not be written at all");
        assertEquals(0, productEntityRepository.count());
        assertEquals(0, productImageEntityRepository.count());
    }

    /**
     * A failure while the rows are being written takes the file with it. The
     * insert is stubbed to fail so the window can be opened on demand; a mocked
     * repository is appropriate here precisely because the point is the failure,
     * and the rollback it causes is still judged by the real database.
     */
    @Test
    void aFailureDuringPersistenceRemovesTheNewFileAndRollsBackTheRows() throws IOException {
        doThrow(new DataIntegrityViolationException("product insert rejected"))
                .when(productEntityRepository).save(any());

        assertThrows(DataIntegrityViolationException.class, () -> adminSupplyService.uploadProduct(
                admin.getId(), "Water", "Still", new BigDecimal("5.00"), 10, BigDecimal.ZERO,
                "Fresh", category.getCategoryId(), null, null, png()));

        assertEquals(0, productEntityRepository.count(),
                "a half-written product would advertise an image the database has no record of");
        assertEquals(0, productImageEntityRepository.count());
        assertEquals(0, storedFiles().count());
    }

    /**
     * The window a {@code catch}-based cleanup cannot see: the method returned
     * normally, nothing failed inside it, and the transaction is only rolled back
     * afterwards. The file has to be removed by the transaction's own outcome,
     * not by the method's own return.
     */
    @Test
    void aRollbackAfterTheMethodReturnedStillRemovesTheUploadedFile() throws IOException {
        TransactionTemplate transaction = new TransactionTemplate(txManager);
        transaction.execute(status -> {
            adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                    new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh",
                    category.getCategoryId(), null, null, png());
            status.setRollbackOnly();
            return null;
        });

        assertEquals(0, productEntityRepository.count());
        assertEquals(0, productImageEntityRepository.count());
        assertEquals(0, storedFiles().count());
    }

    /**
     * The same window on the JSON path, which has no file but does have a row.
     */
    @Test
    void aRollbackAfterTheJsonMethodReturnedRemovesTheProduct() {
        TransactionTemplate transaction = new TransactionTemplate(txManager);
        transaction.execute(status -> {
            adminSupplyService.addProduct(addItemDto(
                    "Coke", "Cola", "1.50", 12, "0.10", "Iced cola", "Beverages"));
            status.setRollbackOnly();
            return null;
        });

        assertEquals(0, productEntityRepository.count());
    }

    /**
     * A product created without an image is a legitimate state, and the edit path
     * has to be able to repair it. A version-checked edit both adds the first
     * image and advances the version, so the sequence an editor follows - create,
     * load the version, save the picture - works end to end.
     */
    @Test
    void aJsonCreatedProductReceivesItsFirstImageOnAVersionCheckedUpdate() throws IOException {
        Long productId = createProduct("Water", "Still", "5.00", 10, "0.00", "Fresh", "Beverages");
        assertEquals(0, productImageEntityRepository.count(),
                "precondition: JSON creation deliberately writes no image");

        long versionBefore = jdbcTemplate.queryForObject(
                "SELECT version FROM product_entity WHERE product_entity_id = ?", Long.class, productId);

        adminSupplyService.updateProduct(admin.getId(), productId, "Sparkling Water", "Sparkling",
                new BigDecimal("7.25"), 42, new BigDecimal("5.00"), "Fizzy",
                category.getCategoryId(), null, null, png(), versionBefore);

        assertEquals(1, productImageEntityRepository.count(),
                "the uploaded image has to become reachable, not just exist on disk");
        assertEquals(1, storedFiles().count());
        assertEquals(versionBefore + 1, jdbcTemplate.queryForObject(
                "SELECT version FROM product_entity WHERE product_entity_id = ?", Long.class, productId));

        String imagePath = jdbcTemplate.queryForObject(
                "SELECT image_path FROM product_image_entity WHERE product_entity_id = ?", String.class, productId);
        assertTrue(Files.exists(resolve(imagePath)),
                "the image row must point at the file that was just uploaded");
    }

    /**
     * The version protocol is unchanged by this work, and a JSON-created product
     * is subject to it exactly like any other: an editor holding a stale version
     * is refused, and the refusal leaves the stored image untouched.
     */
    @Test
    void aJsonCreatedProductStillRejectsAStaleVersionedEdit() throws IOException {
        Long productId = createProduct("Water", "Still", "5.00", 10, "0.00", "Fresh", "Beverages");
        long filesBefore = storedFiles().count();

        assertThrows(ProductEditConflictException.class, () ->
                adminSupplyService.updateProduct(admin.getId(), productId, "Stale Edit", "Stale",
                        new BigDecimal("99.00"), 999, BigDecimal.ZERO, "Rejected",
                        category.getCategoryId(), null, null, png(), 5L));

        assertEquals(0, productImageEntityRepository.count());
        assertEquals(filesBefore, storedFiles().count(),
                "a rejected edit must not write the upload it carries");
        assertEquals(10, jdbcTemplate.queryForObject(
                "SELECT product_count FROM product_entity WHERE product_entity_id = ?",
                Integer.class, productId));
    }

    private Long createProduct(String productName, String subCategoryName, String price,
                               int count, String discount, String description, String categoryName) {
        adminSupplyService.addProduct(addItemDto(productName, subCategoryName, price, count,
                discount, description, categoryName));
        List<Long> ids = jdbcTemplate.queryForList("SELECT product_entity_id FROM product_entity",
                Long.class);
        assertEquals(1, ids.size(), "exactly one product may exist for this test's assertion to mean anything");
        return ids.get(0);
    }

    private Long uploadedProductId(String productName, String subCategoryName, String price,
                                   int count, String discount, String description) throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), productName, subCategoryName,
                new BigDecimal(price), count, new BigDecimal(discount), description,
                category.getCategoryId(), null, null, png());
        assertEquals(2, productEntityRepository.count(),
                "precondition: this is the second product of the pair being compared");
        return jdbcTemplate.queryForObject(
                "SELECT max(product_entity_id) FROM product_entity", Long.class);
    }

    private Map<String, Object> storedFieldsOf(Long productId) {
        return jdbcTemplate.queryForMap(
                "SELECT product_name, subcategory_name, product_count, product_price, "
                        + "product_discount, product_description, admin_entity_id, "
                        + "category_entity_id, version FROM product_entity WHERE product_entity_id = ?",
                productId);
    }

    private AdminAddItemDto addItemDto(String productName, String subCategoryName, String price,
                                       int count, String discount, String description,
                                       String categoryName) {
        ProductDto productDto = new ProductDto();
        productDto.setProductName(productName);
        productDto.setSubCategoryName(subCategoryName);
        productDto.setProductPrice(price == null ? null : new BigDecimal(price));
        productDto.setProductCount(count);
        productDto.setProductDiscount(discount == null ? null : new BigDecimal(discount));
        productDto.setProductDescription(description);
        productDto.setCategoryName(categoryName);

        AdminAddItemDto dto = new AdminAddItemDto();
        dto.setAdminId(admin.getId());
        dto.setProductDto(productDto);
        return dto;
    }

    private Path resolve(String storedPath) {
        Path candidate = Path.of(storedPath);
        return candidate.isAbsolute() ? candidate : UPLOAD_DIR.resolve(candidate.getFileName().toString());
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
            return Files.createTempDirectory("migros-creation-test");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
