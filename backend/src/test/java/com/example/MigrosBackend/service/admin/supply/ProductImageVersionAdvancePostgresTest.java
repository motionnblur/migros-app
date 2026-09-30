package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.exception.admin.ProductEditConflictException;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.product.ProductImageEntityRepository;
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
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The image is not a column on the product row, and that made the edit version
 * lie.
 *
 * <p>{@code product_entity.version} is how every writer of a product is detected,
 * and {@code expectedVersion} is how a stale edit form is refused. Replacing only
 * the picture writes {@code ProductImageEntity} and nothing at all on the product,
 * so Hibernate issues no {@code UPDATE}, the {@code @Version} column never moves,
 * and every open edit form for that product still holds the pre-replacement
 * version and is still considered current. The replacement was therefore invisible
 * to the guard that exists to see it.
 *
 * <p>These tests pin the fix - an image-only replacement advances the version
 * explicitly, via {@code PESSIMISTIC_FORCE_INCREMENT} - and, just as importantly,
 * pin the boundary around it: a genuine no-op must <em>not</em> advance it, a
 * rejected edit must leave neither a file nor a cleanup obligation, and the value
 * the method returns must be the version the row really carries, because that is
 * what the response header hands the editor for its next save.
 *
 * <p>Why the pessimistic variant and not the obvious {@code OPTIMISTIC_FORCE_INCREMENT}:
 * the row is already held at {@code PESSIMISTIC_WRITE} by {@code findByIdForUpdate},
 * and Hibernate's lock upgrade is ordinal-ordered, so a request for a lower lock
 * grade is treated as already satisfied and its body is skipped entirely - the
 * increment is never issued and the version never moves, silently reproducing the
 * defect this class exists to catch. The pessimistic form out-ranks the write lock
 * already held, so it actually runs. {@code anImageOnlyReplacementAdvancesTheVersion}
 * fails if that is ever changed back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class ProductImageVersionAdvancePostgresTest {

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
        // These tests drive the cleanup worker explicitly and assert on the
        // files that exist at a precise moment, so the scheduler must not race
        // them and quietly delete a file out from under an assertion.
        registry.add("product-image.cleanup.scan-ms", () -> "3600000");
        registry.add("product-image.cleanup.initial-delay-ms", () -> "3600000");
        registry.add("product-image.cleanup.retention-scan-ms", () -> "3600000");
        registry.add("product-image.cleanup.retention-initial-delay-ms", () -> "3600000");
        registry.add("product-image.cleanup.backoff-base-seconds", () -> "1");
        registry.add("product-image.cleanup.backoff-max-seconds", () -> "1");
        registry.add("product-image.cleanup.reference-recheck-seconds", () -> "1");
    }

    @MockitoSpyBean
    private ProductImageEntityRepository productImageEntityRepository;

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
        admin.setAdminName("image-admin");
        admin.setItemEntities(new java.util.ArrayList<>());
        admin = adminEntityRepository.saveAndFlush(admin);

        category = new CategoryEntity();
        category.setCategoryId(11);
        category.setCategoryName("image-category");
        category = categoryEntityRepository.saveAndFlush(category);
    }

    // -------------------------------------------------------------------------
    // The defect itself
    // -------------------------------------------------------------------------

    /**
     * The exact regression. Nothing on the product row changes - same name, same
     * subcategory, same price, same count, same discount, same description, same
     * admin, same category - so before the fix Hibernate dirtied no field, issued
     * no {@code UPDATE}, and the version stayed at 0.
     */
    @Test
    void anImageOnlyReplacementAdvancesTheVersion() throws IOException {
        Long productId = uploadWater();
        long versionBeforeReplacement = versionOf(productId);
        String firstPath = storedImagePath();

        long returned = adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(),
                png(), versionBeforeReplacement);

        assertNotEquals(storedImagePath(), firstPath, "precondition: the image really was replaced");
        assertTrue(versionOf(productId) > versionBeforeReplacement,
                "an image-only replacement changes the product, so its edit version must move; "
                        + "otherwise every open edit form still believes it is current");
        assertEquals(versionOf(productId), returned,
                "the returned version is what the editor's next save will submit");
    }

    /**
     * The consequence of the defect, and the reason it mattered: a second editor
     * holding the pre-replacement version is now actually refused.
     */
    @Test
    void aSecondEditorHoldingThePreReplacementVersionIsRejected() throws IOException {
        Long productId = uploadWater();
        long staleVersion = versionOf(productId);

        adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(),
                png(), staleVersion);
        long versionAfterReplacement = versionOf(productId);
        assertTrue(versionAfterReplacement > staleVersion, "precondition: the replacement moved the version");

        assertThrows(ProductEditConflictException.class, () ->
                adminSupplyService.updateProduct(admin.getId(), productId, "Second Editor", "Still",
                        new BigDecimal("99.00"), 777, new BigDecimal("5.00"), "Late write",
                        category.getCategoryId(), null, staleVersion));

        assertEquals("Water", jdbcTemplate.queryForObject(
                "SELECT product_name FROM product_entity WHERE product_entity_id = ?",
                String.class, productId), "a rejected editor's draft must not be applied");
        assertEquals(10, jdbcTemplate.queryForObject(
                "SELECT product_count FROM product_entity WHERE product_entity_id = ?",
                Integer.class, productId));
        assertEquals(versionAfterReplacement, versionOf(productId),
                "a rejected edit advances nothing");
    }

    /**
     * A refused edit that carried an upload must be a complete no-op. The file is
     * the one thing a database rollback cannot undo, and the version comparison
     * runs before a single byte is written precisely so this holds.
     */
    @Test
    void aRejectedEditWritesNoFileAndSchedulesNoCleanup() throws IOException {
        Long productId = uploadWater();
        String originalPath = storedImagePath();
        long filesBefore = storedFiles().count();

        // Another writer advanced the row while this editor's form was open.
        jdbcTemplate.update("UPDATE product_entity SET version = version + 1 WHERE product_entity_id = ?",
                productId);
        long staleVersion = versionOf(productId) - 1;

        assertThrows(ProductEditConflictException.class, () ->
                adminSupplyService.updateProduct(admin.getId(), productId, "Rejected", "Rejected",
                        new BigDecimal("99.00"), 999, BigDecimal.ZERO, "Rejected",
                        category.getCategoryId(), png(), staleVersion));

        assertEquals(filesBefore, storedFiles().count(),
                "a rejected edit must not leave the upload it carried on disk");
        assertEquals(originalPath, storedImagePath(),
                "a rejected edit must leave the image reference exactly as it was");
        assertTrue(pendingCleanupIdentities().isEmpty(),
                "a replacement that never happened makes nothing obsolete, so it must owe no deletion");
    }

    /**
     * The version increment and the image write are one unit of work. A rollback
     * has to take both: the surviving row must carry the version it started with
     * (otherwise a rollback would silently invalidate every open form over a
     * change that was undone) and the uncommitted upload has to be removed by the
     * transaction-synchronization cleanup, because the database cannot.
     */
    @Test
    void aRolledBackReplacementRestoresTheOriginalImageAndVersion() throws IOException {
        Long productId = uploadWater();
        String originalPath = storedImagePath();
        long versionBefore = versionOf(productId);
        long filesBefore = storedFiles().count();

        TransactionTemplate transaction = new TransactionTemplate(txManager);
        transaction.execute(status -> {
            adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                    new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(),
                    png(), versionBefore);
            status.setRollbackOnly();
            return null;
        });

        assertEquals(originalPath, storedImagePath(),
                "the rollback must leave the reference the surviving row still depends on");
        assertEquals(versionBefore, versionOf(productId),
                "an undone edit must not leave the version advanced; that would reject every "
                        + "open form over a change that never committed");
        assertTrue(pendingCleanupIdentities().isEmpty(),
                "after the rollback the old file is still referenced, so nothing is obsolete");
        assertEquals(filesBefore, storedFiles().count(),
                "the uncommitted upload is the one thing the rollback has to remove itself");
        assertTrue(Files.exists(resolve(originalPath)),
                "rollback must never delete a file the surviving row still points at");
    }

    // -------------------------------------------------------------------------
    // The other side of the rule: no change at all means no version movement
    // -------------------------------------------------------------------------

    /**
     * The deliberate rule, and its boundary: <em>no change at all implies the
     * version does not move</em>.
     *
     * <p>Re-saving a form whose fields are identical to what is already stored is
     * an ordinary no-op. Advancing the version for it would invalidate every other
     * administrator's open form over a change that did not happen, which turns the
     * guard from a protection into an obstacle. The rule is therefore narrow and
     * two-sided: an <em>image</em> change alone does move the version (the
     * defect's fix), and a submission that changes nothing - including the image -
     * does not.
     *
     * <p>It is also stable, not merely absent: a second identical no-op submitted
     * against the version the first one left behind must also succeed, which is
     * only true if the first one really did leave the version where it found it.
     */
    @Test
    void aGenuineNoOpLeavesTheVersionUnmovedAndStaysSuccessful() throws IOException {
        Long productId = uploadWater();
        long versionAfterCreation = versionOf(productId);

        long firstReturned = adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(),
                null, versionAfterCreation);

        assertEquals(versionAfterCreation, firstReturned,
                "a submission that changes nothing must report the version it found");
        assertEquals(versionAfterCreation, versionOf(productId),
                "no change at all implies the version does not move");

        long secondReturned = adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(),
                null, firstReturned);

        assertEquals(versionAfterCreation, secondReturned);
        assertEquals(versionAfterCreation, versionOf(productId),
                "repeating a no-op is still a no-op, so it must not drift the version either");
    }

    // -------------------------------------------------------------------------
    // What the returned value means to the HTTP layer
    // -------------------------------------------------------------------------

    /**
     * The header carries this number, and the editor is entitled to chain its next
     * save onto it. A value that were merely "some version" - read separately,
     * after the commit - could already be past a checkout reservation the editor's
     * form never saw, and the next absolute count write would then resurrect the
     * reserved units. So the returned value has to be the version the transaction
     * actually left on the row, for both ways an edit can change a product.
     */
    @Test
    void theReturnedValueIsTheRealPostChangeVersionForBothKindsOfEdit() throws IOException {
        Long productId = uploadWater();
        long versionBeforeImageEdit = versionOf(productId);

        long afterImageEdit = adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(),
                png(), versionBeforeImageEdit);

        assertEquals(versionOf(productId), afterImageEdit,
                "the value carried by the response header for an image-changing edit");

        long versionBeforeFieldEdit = versionOf(productId);
        long afterFieldEdit = adminSupplyService.updateProduct(admin.getId(), productId, "Sparkling", "Fizzy",
                new BigDecimal("7.25"), 12, new BigDecimal("5.00"), "Fizzy", category.getCategoryId(),
                null, versionBeforeFieldEdit);

        assertEquals(versionOf(productId), afterFieldEdit,
                "the value carried by the response header for a field-changing edit");
        assertTrue(afterFieldEdit > afterImageEdit,
                "each accepted edit advances the version by exactly one step");
    }

    /**
     * The normal editing loop, end to end: save, learn the new version from the
     * return value, save again. If the returned value were wrong in either
     * direction this would either reject the editor against its own successful
     * write or, worse, skip a version and let a stale form back in.
     */
    @Test
    void twoConsecutiveSavesChainingTheReturnedVersionBothSucceed() throws IOException {
        Long productId = uploadWater();
        long startingVersion = versionOf(productId);

        long afterFirst = adminSupplyService.updateProduct(admin.getId(), productId, "First Save", "Still",
                new BigDecimal("5.00"), 11, BigDecimal.ZERO, "Fresh", category.getCategoryId(),
                null, startingVersion);

        long afterSecond = adminSupplyService.updateProduct(admin.getId(), productId, "Second Save", "Still",
                new BigDecimal("5.00"), 12, BigDecimal.ZERO, "Fresh", category.getCategoryId(),
                null, afterFirst);

        assertEquals(startingVersion + 1, afterFirst, "one accepted save advances the version by one");
        assertEquals(startingVersion + 2, afterSecond,
                "and the editor chaining the returned value is not rejected against its own write");
        assertEquals(startingVersion + 2, versionOf(productId));
        assertEquals("Second Save", jdbcTemplate.queryForObject(
                "SELECT product_name FROM product_entity WHERE product_entity_id = ?",
                String.class, productId));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Long uploadWater() {
        adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), png());
        return productEntityRepository.findAll().get(0).getId();
    }

    private String storedImagePath() {
        return jdbcTemplate.queryForObject("SELECT image_path FROM product_image_entity", String.class);
    }

    private List<String> pendingCleanupIdentities() {
        return jdbcTemplate.queryForList(
                "SELECT file_identity FROM product_image_cleanup_entity "
                        + "WHERE status IN ('PENDING', 'PROCESSING') ORDER BY sequence_no",
                String.class);
    }

    private long versionOf(Long productId) {
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM product_entity WHERE product_entity_id = ?", Long.class, productId);
        return version == null ? 0L : version;
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
