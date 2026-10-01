package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.exception.admin.ProductEditConflictException;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    private ProductImageCleanupWorker cleanupWorker;
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

    @Test
    void aSuccessfulUploadPersistsTheProductAndItsImageTogether() throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png());

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
                    new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png());
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
                        new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png()));

        assertEquals(0, productEntityRepository.count(),
                "a half-written product would advertise an image the database has no record of");
        assertEquals(0, storedFiles().count());
    }

    @Test
    void anUpdateReplacesTheExistingImageRow() throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png());
        Long productId = productEntityRepository.findAll().get(0).getId();
        String firstPath = jdbcTemplate.queryForObject(
                "SELECT image_path FROM product_image_entity", String.class);
        assertNotNull(firstPath);

        adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png(), 0L);

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
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png());
        Long productId = productEntityRepository.findAll().get(0).getId();

        jdbcTemplate.update("DELETE FROM product_image_entity WHERE product_entity_id = ?", productId);
        assertEquals(0, productImageEntityRepository.count());

        adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png(), 0L);

        List<String> paths = jdbcTemplate.queryForList("SELECT image_path FROM product_image_entity", String.class);
        assertEquals(1, paths.size(), "the uploaded image has to become reachable, not just exist on disk");
        assertTrue(Files.exists(resolve(paths.get(0))),
                "the row must point at the file that was just uploaded");
        assertEquals(2, storedFiles().count());
    }

    /**
     * A rejected edit must be a complete no-op, including for the image.
     *
     * <p>The file is the one thing a database rollback cannot undo. If the
     * version check ran after the upload - or if the rejected transaction had
     * already written a file before failing - the rejected draft would leave a
     * stray upload behind, and pointing the product at it would be exactly the
     * silent overwrite the version check exists to prevent.
     */
    @Test
    void aRejectedEditWritesNoFileAndChangesNoImageReference() throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png());
        Long productId = productEntityRepository.findAll().get(0).getId();
        String originalPath = jdbcTemplate.queryForObject(
                "SELECT image_path FROM product_image_entity", String.class);
        long filesBefore = storedFiles().count();

        // Something else advanced the row while the editor was open.
        jdbcTemplate.update("UPDATE product_entity SET version = version + 1 WHERE product_entity_id = ?",
                productId);

        assertThrows(ProductEditConflictException.class, () ->
                adminSupplyService.updateProduct(admin.getId(), productId, "Stale Edit", "Stale Sub",
                        new BigDecimal("99.00"), 999, BigDecimal.ZERO, "Rejected", category.getCategoryId(), null, null, png(), 0L));

        assertEquals(filesBefore, storedFiles().count(),
                "a rejected edit must not write the upload it carries");
        assertEquals(originalPath, jdbcTemplate.queryForObject(
                "SELECT image_path FROM product_image_entity", String.class),
                "a rejected edit must leave the image reference exactly as it was");
        assertEquals(10, jdbcTemplate.queryForObject(
                "SELECT product_count FROM product_entity WHERE product_entity_id = ?",
                Integer.class, productId));
        assertEquals("Water", jdbcTemplate.queryForObject(
                "SELECT product_name FROM product_entity WHERE product_entity_id = ?",
                String.class, productId));
    }

    /**
     * A successful edit advances the version it was submitted against.
     *
     * <p>Two things depend on this and neither is visible in a single-edit test:
     * the editor has to learn the new version, or its next save conflicts with
     * its own successful write; and a second editor must be rejected, or two
     * administrators silently overwrite each other.
     */
    @Test
    void aFreshVersionEditSucceedsAndAdvancesTheVersion() throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png());
        Long productId = productEntityRepository.findAll().get(0).getId();
        long versionBefore = versionOf(productId);

        adminSupplyService.updateProduct(admin.getId(), productId, "Sparkling Water", "Sparkling",
                new BigDecimal("7.25"), 42, new BigDecimal("5.00"), "Fizzy", category.getCategoryId(), null, null, null, versionBefore);

        assertEquals(versionBefore + 1, versionOf(productId),
                "the entity's @Version must advance on a managed product write");

        ProductEntity reloaded = productEntityRepository.findById(productId).orElseThrow();
        assertEquals("Sparkling Water", reloaded.getProductName());
        assertEquals("Sparkling", reloaded.getSubcategoryName());
        assertEquals(0, new BigDecimal("7.25").compareTo(reloaded.getProductPrice()));
        assertEquals(42, reloaded.getProductCount());
        assertEquals(0, new BigDecimal("5.00").compareTo(reloaded.getProductDiscount()));
    }

    /**
     * Two administrators open the same product at the same version. Exactly one
     * may win; the loser must be told to reload, not silently merged.
     */
    @Test
    void twoEditorsOfTheSameVersionCannotBothWin() throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png());
        Long productId = productEntityRepository.findAll().get(0).getId();
        long sharedVersion = versionOf(productId);

        adminSupplyService.updateProduct(admin.getId(), productId, "First Editor", "Still",
                new BigDecimal("5.00"), 11, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, null, sharedVersion);

        assertThrows(ProductEditConflictException.class, () ->
                adminSupplyService.updateProduct(admin.getId(), productId, "Second Editor", "Still",
                        new BigDecimal("5.00"), 12, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, null, sharedVersion));

        ProductEntity reloaded = productEntityRepository.findById(productId).orElseThrow();
        assertEquals("First Editor", reloaded.getProductName());
        assertEquals(11, reloaded.getProductCount(),
                "the loser's absolute count must not overwrite the winner's");
        assertEquals(sharedVersion + 1, versionOf(productId),
                "exactly one edit may advance the version");
    }

    /**
     * The whole point of a durable queue: the file the product stopped pointing
     * at survives the commit as a record, not just as a file. Before this, the
     * only thing that knew the old path was obsolete was the request thread, so
     * the bytes were removed by luck or leaked by a crash.
     */
    @Test
    void aSuccessfulReplacementCommitsTheNewReferenceAndOwesTheOldFile() throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png());
        Long productId = productEntityRepository.findAll().get(0).getId();
        Path firstPath = resolve(jdbcTemplate.queryForObject(
                "SELECT image_path FROM product_image_entity", String.class));

        adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png(), 0L);

        String secondStoredPath = jdbcTemplate.queryForObject(
                "SELECT image_path FROM product_image_entity", String.class);
        assertNotEquals(firstPath.toString(), secondStoredPath, "precondition: the image was replaced");
        assertEquals(List.of(firstPath.getFileName().toString()), pendingCleanupIdentities(),
                "the file the product stopped referencing is owed a deletion, durably");
        assertTrue(Files.exists(firstPath), "the old file still exists until the worker removes it");

        assertEquals(1, cleanupWorker.cleanupDueFiles());

        assertFalse(Files.exists(firstPath), "the obsolete file must eventually be removed");
        assertTrue(Files.exists(resolve(secondStoredPath)),
                "the file the product now points at must never be deleted");
        assertEquals(0, pendingCleanupIdentities().size());
    }

    /**
     * A product that somehow carries several image rows owns several files.
     * Taking only the row the catalog happens to display first would leave the
     * others on disk with nothing recording that they are unreferenced.
     */
    @Test
    void aSuccessfulDeletionSchedulesEveryImageFileForCleanup() throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png());
        Long productId = productEntityRepository.findAll().get(0).getId();
        String firstStoredPath = jdbcTemplate.queryForObject(
                "SELECT image_path FROM product_image_entity", String.class);

        // A second row, the way duplicate or historical rows exist in a database
        // that predates the one-row-per-product shape.
        Files.writeString(UPLOAD_DIR.resolve("image_legacy_second.png"), "second");
        jdbcTemplate.update("INSERT INTO product_image_entity (image_path, product_entity_id) "
                + "VALUES (?, ?)", "image_legacy_second.png", productId);

        adminSupplyService.deleteProduct(productId);

        assertEquals(0, productEntityRepository.count());
        assertEquals(0, productImageEntityRepository.count());
        assertEquals(2, pendingCleanupIdentities().size(),
                "every image the deleted product owned is owed a deletion, not just the first");
        assertTrue(pendingCleanupIdentities().contains("image_legacy_second.png"));

        assertEquals(2, cleanupWorker.cleanupDueFiles());

        assertFalse(Files.exists(resolve(firstStoredPath)));
        assertFalse(Files.exists(UPLOAD_DIR.resolve("image_legacy_second.png")));
    }

    /**
     * The obligation is written in the same transaction as the delete, so a
     * delete that cannot happen owes nothing. A product still referenced by a
     * checkout is refused by the foreign key; recording a cleanup for its image
     * anyway would delete the picture of a product that is still being sold.
     */
    @Test
    void aFailedDeletionCommitsNoCleanupWork() throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png());
        Long productId = productEntityRepository.findAll().get(0).getId();
        String storedPath = jdbcTemplate.queryForObject(
                "SELECT image_path FROM product_image_entity", String.class);
        long userId = insertCheckoutReferencing(productId);

        assertThrows(RuntimeException.class, () -> adminSupplyService.deleteProduct(productId));

        assertEquals(1, productEntityRepository.count(), "the product must survive a refused deletion");
        assertEquals(0, pendingCleanupIdentities().size(),
                "an obligation for a product that still exists would delete a live image");
        assertTrue(Files.exists(resolve(storedPath)),
                "the file is still referenced and must still be there");
        assertEquals(0, cleanupWorker.cleanupDueFiles());
        assertTrue(Files.exists(resolve(storedPath)));
        deleteCheckoutsOf(userId);
    }

    /**
     * The failure a {@code catch}-based cleanup cannot see, on the replacement
     * path. The method returned normally and the edit had already been applied
     * in memory; only the commit that followed rolls it back. Everything the
     * rollback undoes - the new reference, the new upload, and the recorded
     * obligation - has to go together, and what it must leave behind is the old
     * file that the product is still pointing at.
     */
    @Test
    void aRolledBackReplacementKeepsTheOldReferenceAndOwesNothing() throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png());
        Long productId = productEntityRepository.findAll().get(0).getId();
        String firstStoredPath = jdbcTemplate.queryForObject(
                "SELECT image_path FROM product_image_entity", String.class);
        long filesBefore = Files.list(UPLOAD_DIR).count();

        TransactionTemplate transaction = new TransactionTemplate(txManager);
        transaction.execute(status -> {
            adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                    new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png(), 0L);
            status.setRollbackOnly();
            return null;
        });

        assertEquals(firstStoredPath, jdbcTemplate.queryForObject(
                "SELECT image_path FROM product_image_entity", String.class),
                "a rolled-back replacement must leave the reference it started with");
        assertEquals(0, pendingCleanupIdentities().size(),
                "the old file is still referenced after the rollback, so nothing is obsolete");
        assertTrue(Files.exists(resolve(firstStoredPath)),
                "rollback must never delete a file the surviving row still points at");
        assertEquals(filesBefore, Files.list(UPLOAD_DIR).count(),
                "the uncommitted upload is the one thing the rollback has to remove");
        assertEquals(0, cleanupWorker.cleanupDueFiles());
        assertTrue(Files.exists(resolve(firstStoredPath)));
    }

    /**
     * If the obligation cannot be recorded, the edit must not stand. Committing
     * a replacement whose cleanup row failed to write is how a file becomes
     * unreferenced with nothing anywhere recording it, which is the exact state
     * this queue exists to make impossible.
     */
    @Test
    void aReplacementThatCannotRecordItsCleanupDoesNotCommit() throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png());
        Long productId = productEntityRepository.findAll().get(0).getId();
        String firstStoredPath = jdbcTemplate.queryForObject(
                "SELECT image_path FROM product_image_entity", String.class);
        long filesBefore = Files.list(UPLOAD_DIR).count();

        doThrow(new IllegalStateException("cleanup queue unavailable"))
                .when(cleanupStore).enqueue(anyString(), any());

        assertThrows(IllegalStateException.class, () ->
                adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                        new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png(), 0L));

        assertEquals(firstStoredPath, jdbcTemplate.queryForObject(
                "SELECT image_path FROM product_image_entity", String.class));
        assertEquals(filesBefore, Files.list(UPLOAD_DIR).count());
        assertTrue(Files.exists(resolve(firstStoredPath)));
    }

    /**
     * The step-2 contract, restated against the queue: a stale editor is
     * refused before anything is written, so it must also produce no obligation.
     * A rejected edit that recorded cleanup work would delete the file the
     * product is still serving.
     */
    @Test
    void aStaleVersionEditCreatesNeitherUploadNorCleanupWork() throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png());
        Long productId = productEntityRepository.findAll().get(0).getId();
        String originalPath = jdbcTemplate.queryForObject(
                "SELECT image_path FROM product_image_entity", String.class);
        long filesBefore = Files.list(UPLOAD_DIR).count();

        jdbcTemplate.update("UPDATE product_entity SET version = version + 1 WHERE product_entity_id = ?",
                productId);

        assertThrows(ProductEditConflictException.class, () ->
                adminSupplyService.updateProduct(admin.getId(), productId, "Stale Edit", "Stale Sub",
                        new BigDecimal("99.00"), 999, BigDecimal.ZERO, "Rejected",
                        category.getCategoryId(), null, null, png(), 0L));

        assertEquals(0, pendingCleanupIdentities().size(),
                "a refused edit must not record an obligation for a replacement that never happened");
        assertEquals(filesBefore, Files.list(UPLOAD_DIR).count());
        assertEquals(originalPath, jdbcTemplate.queryForObject(
                "SELECT image_path FROM product_image_entity", String.class));
    }

    private Long insertCheckoutReferencing(Long productId) {
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO user_entity (user_mail) VALUES ('checkout-holder@migros.com') "
                        + "ON CONFLICT DO NOTHING RETURNING user_entity_id", Long.class);
        if (userId == null) {
            userId = jdbcTemplate.queryForObject(
                    "SELECT user_entity_id FROM user_entity WHERE user_mail = 'checkout-holder@migros.com'",
                    Long.class);
        }
        assertNotNull(userId);
        java.util.UUID checkoutId = jdbcTemplate.queryForObject(
                "INSERT INTO checkout_entity (checkout_id, user_entity_id, status, total_amount, "
                        + "amount_minor, currency, created_at, expires_at, updated_at, version) "
                        + "VALUES (?, ?, 'PREPARED', 5.00, 500, 'try', now(), "
                        + "now() + interval '1 hour', now(), 0) RETURNING checkout_id",
                java.util.UUID.class, java.util.UUID.randomUUID(), userId);
        jdbcTemplate.update("INSERT INTO checkout_item_entity (checkout_id, product_entity_id, "
                + "product_name, quantity, unit_price, line_total) VALUES (?, ?, 'Water', 1, 5.00, 5.00)",
                checkoutId, productId);
        return userId;
    }

    private void deleteCheckoutsOf(Long userId) {
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM checkout_entity WHERE user_entity_id = ?", userId);
        }
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

    /**
     * A newly created product must land on version 0, not null.
     *
     * <p>The entity field starts out null and the insert is a JPA insert, not the
     * migration's {@code DEFAULT 0}. If Hibernate wrote null here the column's
     * NOT NULL would reject the insert; if it somehow wrote null anyway, every
     * later version comparison would fail permanently for that product and no
     * administrator could ever edit it again.
     */
    @Test
    void aNewProductStartsAtVersionZero() throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png());

        Long productId = productEntityRepository.findAll().get(0).getId();
        Long stored = jdbcTemplate.queryForObject(
                "SELECT version FROM product_entity WHERE product_entity_id = ?", Long.class, productId);

        assertNotNull(stored, "a JPA-inserted product must still carry a usable version");
        assertEquals(0L, stored);
    }

    /**
     * An edit that changes nothing on the product row leaves the version alone.
     *
     * <p>Submitting the same field values again is a common no-op, and advancing
     * the version for it would invalidate every other administrator's open form
     * over a change that never happened, turning the guard into an obstacle
     * rather than a protection.
     *
     * <p>This test previously passed a re-uploaded picture instead of no image,
     * on the premise that re-sending the same picture was a no-op. It never was,
     * and finding the truth of that is what the image-version defect was built
     * on: an upload always draws a fresh UUID name, so the product is genuinely
     * re-pointed at a different file and the old one becomes durably obsolete.
     * That is a real change to the product and the version must move for it -
     * otherwise every open edit form over the product stays current while the
     * image it displays has silently been replaced underneath it, and either
     * editor can overwrite the other's work with no conflict at all. The version
     * not moving is now asserted where the premise holds, with no image
     * supplied at all, in {@code aGenuineNoOpLeavesTheVersionUnmovedAndStaysSuccessful}.
     */
    @Test
    void anEditThatChangesNothingDoesNotAdvanceTheVersion() throws IOException {
        adminSupplyService.uploadProduct(admin.getId(), "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, png());
        Long productId = productEntityRepository.findAll().get(0).getId();

        adminSupplyService.updateProduct(admin.getId(), productId, "Water", "Still",
                new BigDecimal("5.00"), 10, BigDecimal.ZERO, "Fresh", category.getCategoryId(), null, null, null, 0L);

        assertEquals(0L, versionOf(productId),
                "a no-op edit must not invalidate other editors' open forms");
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
