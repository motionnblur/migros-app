package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.product.ProductImageEntityRepository;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * A product row and the image that belongs to it are one unit of work.
 *
 * <p>The file itself cannot be rolled back by the database, which makes the two
 * failure windows asymmetric and both of them had to be closed: a failure while
 * the rows are being written, and a rollback that only happens after the service
 * method has already returned successfully.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class ProductImageTransactionPostgresTest {

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
    }

    @MockitoSpyBean
    private ProductImageEntityRepository productImageEntityRepository;

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
        jdbcTemplate.execute("TRUNCATE TABLE product_image_entity, product_entity, "
                + "admin_entity, category_entity RESTART IDENTITY CASCADE");
        deleteUploads();

        admin = new AdminEntity();
        admin.setAdminName("image-admin");
        admin.setItemEntities(new java.util.ArrayList<>());
        admin = adminEntityRepository.saveAndFlush(admin);

        category = new CategoryEntity();
        category.setCategoryId(11);
        category.setCategoryName("image-category");
        category = categoryEntityRepository.saveAndFlush(category);
    }

    @Test
    void aSuccessfulUploadPersistsTheProductAndItsImageTogether() throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), png());

        assertEquals(1, productEntityRepository.count());
        assertEquals(1, productImageEntityRepository.count());
        assertEquals(1, storedFiles().count(),
                "a committed upload must leave exactly one reachable file behind");
    }

    /**
     * The failure a {@code catch}-based cleanup cannot see: the method returned
     * normally and nothing went wrong inside it - the transaction is only rolled
     * back afterwards.
     */
    @Test
    void aRollbackAfterTheMethodReturnedStillRemovesTheUploadedFile() throws IOException {
        TransactionTemplate transaction = new TransactionTemplate(txManager);
        transaction.execute(status -> {
            adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                    new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), png());
            status.setRollbackOnly();
            return null;
        });

        assertEquals(0, productEntityRepository.count(), "the product row must not survive the rollback");
        assertEquals(0, productImageEntityRepository.count(), "the image row must not survive the rollback");
        assertEquals(0, storedFiles().count(),
                "the file is the one thing a rollback cannot undo, so it has to be cleaned up explicitly");
    }

    @Test
    void aRejectedImageRowLeavesNeitherRowNorFileBehind() throws IOException {
        doThrow(new DataIntegrityViolationException("image insert rejected"))
                .when(productImageEntityRepository).save(any());

        assertThrows(DataIntegrityViolationException.class, () ->
                adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                        new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), png()));

        assertEquals(0, productEntityRepository.count(),
                "a half-written product would advertise an image the database has no record of");
        assertEquals(0, storedFiles().count());
    }

    @Test
    void anUpdateReplacesTheExistingImageRow() throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), png());
        Long productId = productEntityRepository.findAll().get(0).getId();
        String firstPath = jdbcTemplate.queryForObject(
                "SELECT image_path FROM product_image_entity", String.class);
        assertNotNull(firstPath);

        adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), png());

        assertEquals(1, productImageEntityRepository.count(), "an edit must not add a second image row");
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT image_path FROM product_image_entity", String.class));
    }

    /**
     * A product created before images existed has no row to update. Skipping the
     * write because "there is nothing to update" leaves the uploaded file on disk
     * with nothing referencing it, forever.
     */
    @Test
    void anUpdateToAProductWithoutAnImageRowCreatesOne() throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), png());
        Long productId = productEntityRepository.findAll().get(0).getId();

        jdbcTemplate.update("DELETE FROM product_image_entity WHERE product_entity_id = ?", productId);
        assertEquals(0, productImageEntityRepository.count());

        adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), png());

        List<String> paths = jdbcTemplate.queryForList("SELECT image_path FROM product_image_entity", String.class);
        assertEquals(1, paths.size(), "the uploaded image has to become reachable, not just exist on disk");
        assertTrue(Files.exists(resolve(paths.get(0))),
                "the row must point at the file that was just uploaded");
        assertEquals(2, storedFiles().count());
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
            return Files.createTempDirectory("migros-upload-test");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
