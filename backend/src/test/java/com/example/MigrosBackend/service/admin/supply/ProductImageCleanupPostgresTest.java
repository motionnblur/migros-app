package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.service.global.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cleanup queue has to survive the boundaries a file deletion actually has:
 * a commit, a process that dies holding the claim, a disk that refuses, a file
 * two products still share, and two instances running the worker at once.
 *
 * <p>Nothing here is mocked at the boundary that matters. The store, the
 * repository, the constraints and the lease arithmetic are the real ones, so a
 * claim scan that could not see a row, or a fence that did not hold, fails here
 * rather than in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class ProductImageCleanupPostgresTest {

    private static final String USER_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String ADMIN_SECRET = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";
    private static final long LEASE_SECONDS = 60L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    private static final Path UPLOAD_DIR = createUploadDir();
    private static final Path OUTSIDE_DIR = createUploadDir();

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
        // Driven explicitly here; a scheduler firing mid-assertion would delete
        // the very file a test is about to check.
        registry.add("product-image.cleanup.scan-ms", () -> "3600000");
        registry.add("product-image.cleanup.initial-delay-ms", () -> "3600000");
        registry.add("product-image.cleanup.retention-scan-ms", () -> "3600000");
        registry.add("product-image.cleanup.retention-initial-delay-ms", () -> "3600000");
        registry.add("product-image.cleanup.backoff-base-seconds", () -> "1");
        registry.add("product-image.cleanup.backoff-max-seconds", () -> "1");
        registry.add("product-image.cleanup.reference-recheck-seconds", () -> "1");
    }

    @Autowired
    private ProductImageCleanupStore store;
    @Autowired
    private ProductImageCleanupQueue queue;
    @Autowired
    private ProductImageCleanupWorker worker;
    @Autowired
    private com.example.MigrosBackend.repository.product.ProductImageEntityRepository productImageRepository;
    @Autowired
    private FileService fileService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private Clock clock;

    @BeforeEach
    void resetState() throws IOException {
        jdbcTemplate.execute("TRUNCATE TABLE product_image_cleanup_entity, product_image_entity, "
                + "product_entity RESTART IDENTITY CASCADE");
        // Contents only: the roots themselves are the upload directory the
        // application is configured with, and removing one would leave the
        // worker writing into a path that no longer exists.
        for (Path child : listAll(UPLOAD_DIR)) {
            deleteRecursively(child);
        }
        for (Path child : listAll(OUTSIDE_DIR)) {
            deleteRecursively(child);
        }
    }

    // ------------------------------------------------------------------
    // Enqueue: identity, deduplication, and what is deliberately not recorded.
    // ------------------------------------------------------------------

    @Test
    void twoSpellingsOfOneFileProduceOneOutstandingObligation() throws IOException {
        writeUpload("image_shared.png");

        // A bare name and a legacy absolute path are the same file, and two
        // products can easily be stored that way. Recording them as two rows
        // would send two workers at one unlink.
        queue.enqueueObsoleteReference("image_shared.png");
        queue.enqueueObsoleteReference(OUTSIDE_DIR.resolve("image_shared.png").toString());

        assertEquals(List.of("image_shared.png"), outstandingIdentities());
    }

    @Test
    void aReferenceThatNamesNoFileIsNotRecorded() {
        queue.enqueueObsoleteReference("..");
        queue.enqueueObsoleteReference(".");
        queue.enqueueObsoleteReference("nested\\");
        queue.enqueueObsoleteReference("C:image_x.png");
        queue.enqueueObsoleteReference("   ");
        queue.enqueueObsoleteReference(null);

        assertEquals(List.of(), outstandingIdentities(),
                "an obligation for a value that names no file inside the upload directory could "
                        + "only ever be a permanent failure nobody could act on");
    }

    @Test
    void theSchemaRefusesAnIdentityThatIsNotAConfinedFileName() {
        assertRejectedBySchema(() -> jdbcTemplate.update(
                "INSERT INTO product_image_cleanup_entity (cleanup_id, file_identity, status, "
                        + "attempt_count, next_attempt_at, created_at) "
                        + "VALUES ('c1', '../escape.png', 'PENDING', 0, now(), now())"));
        assertRejectedBySchema(() -> jdbcTemplate.update(
                "INSERT INTO product_image_cleanup_entity (cleanup_id, file_identity, status, "
                        + "attempt_count, next_attempt_at, created_at) "
                        + "VALUES ('c2', 'nested\\escape.png', 'PENDING', 0, now(), now())"));
    }

    // ------------------------------------------------------------------
    // Worker: process gap, idempotence, retry.
    // ------------------------------------------------------------------

    /**
     * The reason the queue is durable rather than an after-commit callback: the
     * process that recorded the obligation is gone, and a worker that has never
     * heard of it still finds it and finishes the job.
     */
    @Test
    void aFreshWorkerInstanceFinishesWorkCommittedBeforeItExisted() throws IOException {
        Path file = writeUpload("image_abandoned.png");
        queue.enqueueObsoleteReference(file.getFileName().toString());

        // A second worker, built here rather than injected: nothing it does may
        // depend on state the first one happened to hold.
        ProductImageCleanupWorker restarted = newWorker();

        assertEquals(1, restarted.cleanupDueFiles());
        assertFalse(Files.exists(file));
        assertEquals(1, completedCount());
    }

    /**
     * Deleting a file that is already gone is the desired state, not a failure.
     * Reporting it as a failure would re-arm the row forever and fill the
     * operator log with work that is already finished.
     */
    @Test
    void anAlreadyMissingFileIsCompletedRatherThanRetried() {
        queue.enqueueObsoleteReference("image_never_existed.png");

        assertEquals(1, worker.cleanupDueFiles());
        assertEquals(1, completedCount());
        assertEquals(0, pendingCount());
    }

    @Test
    void aFailedDeletionIsRetriedAndSucceedsOnceTheFileCanBeRemoved() throws IOException {
        // A non-empty directory in place of the file makes the unlink fail on
        // every platform, which is the cheapest reproducible stand-in for a
        // disk that is refusing.
        blockDeletion("image_locked.png");
        queue.enqueueObsoleteReference("image_locked.png");

        assertEquals(0, worker.cleanupDueFiles());
        assertEquals(1, pendingCount(), "a refused deletion is still work owed");
        assertEquals(1, attemptCountOfOnlyRow());
        assertTrue(blockedAt("image_locked.png"), "nothing may have been removed");
        assertTrue(store.findDue(50, LocalDateTime.now(clock)).isEmpty(),
                "the backoff has not elapsed, so nothing is claimable yet");

        makeNextAttemptDue();
        assertEquals(0, worker.cleanupDueFiles(), "still obstructed, so still a failure");
        assertEquals(2, attemptCountOfOnlyRow());

        // The obstruction goes away: the retry has to succeed rather than having
        // given up somewhere along the way.
        unblockDeletion("image_locked.png");
        writeUpload("image_locked.png");
        makeNextAttemptDue();

        assertEquals(1, worker.cleanupDueFiles());
        assertEquals(0, pendingCount());
        assertFalse(Files.exists(UPLOAD_DIR.resolve("image_locked.png")));
    }

    @Test
    void cleanupIsStillOwedAfterTheAttemptThresholdIsPassed() throws IOException {
        blockDeletion("image_never_removable.png");
        queue.enqueueObsoleteReference("image_never_removable.png");

        for (int attempt = 1; attempt <= 4; attempt++) {
            makeNextAttemptDue();
            assertEquals(0, worker.cleanupDueFiles());
        }

        // The threshold is a logging escalation, never a give-up point. Parking
        // the row terminally would abandon a file that is still on disk and
        // still unreferenced, and would look like a clean queue while doing it.
        assertEquals(1, pendingCount());
        assertEquals(4, attemptCountOfOnlyRow());
        assertTrue(store.findDue(50, LocalDateTime.now(clock)).isEmpty(),
                "the row must still be owed rather than abandoned");

        unblockDeletion("image_never_removable.png");
        makeNextAttemptDue();
        assertEquals(1, worker.cleanupDueFiles(), "and it must still be completable afterwards");
    }

    // ------------------------------------------------------------------
    // Leases, fencing, and multiple instances.
    // ------------------------------------------------------------------

    @Test
    void workAbandonedByADeadWorkerIsReclaimedOnceTheLeaseExpires() throws IOException {
        Path file = writeUpload("image_worker_died.png");
        String cleanupId = enqueueAndReturnId("image_worker_died.png");

        assertTrue(store.tryClaim(cleanupId, "dead-worker", LocalDateTime.now(clock), LEASE_SECONDS).isPresent());
        assertTrue(store.findDue(50, LocalDateTime.now(clock)).isEmpty(),
                "a live lease must keep other workers away");

        // The worker never came back, so its bounded lease lapses.
        expireLease(cleanupId);

        assertEquals(List.of(cleanupId), store.findDue(50, LocalDateTime.now(clock)));
        assertEquals(1, worker.cleanupDueFiles());
        assertFalse(Files.exists(file));
    }

    @Test
    void aWorkerThatLostItsLeaseCannotCloseOrRescheduleAnotherWorkersClaim() {
        String cleanupId = enqueueAndReturnId("image_fenced.png");

        assertTrue(store.tryClaim(cleanupId, "first-worker", LocalDateTime.now(clock), LEASE_SECONDS).isPresent());

        // Another instance reclaims the expired row, replacing the lease token.
        expireLease(cleanupId);
        assertTrue(store.tryClaim(cleanupId, "second-worker", LocalDateTime.now(clock), LEASE_SECONDS).isPresent());

        assertEquals(ProductImageCleanupStore.Transition.STALE_CLAIM,
                store.markCompleted(cleanupId, "first-worker", LocalDateTime.now(clock)));
        assertEquals(ProductImageCleanupStore.Transition.STALE_CLAIM,
                store.scheduleRetry(cleanupId, "first-worker", "boom", LocalDateTime.now(clock)));
        assertEquals(ProductImageCleanupStore.Transition.STALE_CLAIM,
                store.markCompleted(cleanupId, null, LocalDateTime.now(clock)));

        assertEquals("PROCESSING", statusOfOnlyRow(),
                "a stale worker must not be able to close or reschedule someone else's claim");
    }

    /**
     * Two instances scanning the same page must not both be credited with the
     * same deletion. A duplicate unlink would in fact be harmless, but the
     * accounting is what tells an operator the queue is being drained, so it has
     * to be exactly right.
     */
    @Test
    void twoWorkersNeverBothAcknowledgeTheSameObligation() throws Exception {
        for (int i = 0; i < 8; i++) {
            queue.enqueueObsoleteReference("image_concurrent_" + i + ".png");
        }

        ProductImageCleanupWorker other = newWorker();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> {
                start.await();
                return worker.cleanupDueFiles();
            });
            Future<Integer> second = pool.submit(() -> {
                start.await();
                return other.cleanupDueFiles();
            });
            start.countDown();

            int completed = first.get(60, TimeUnit.SECONDS) + second.get(60, TimeUnit.SECONDS);
            assertEquals(8, completed,
                    "an obligation completed twice would be one file deleted by two workers and one "
                            + "row credited twice");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(8, completedCount());
        assertEquals(0, pendingCount());
    }

    // ------------------------------------------------------------------
    // The reference check: what stops a live file from being deleted.
    // ------------------------------------------------------------------

    @Test
    void aSharedLegacyFileSurvivesUntilItsLastReferenceIsRemoved() throws IOException {
        Path file = writeUpload("image_shared.png");
        insertImageRow("image_shared.png");
        insertImageRow(UPLOAD_DIR.resolve("nested/image_shared.png").toString());
        queue.enqueueObsoleteReference("image_shared.png");

        assertEquals(0, worker.cleanupDueFiles());
        assertEquals(1, pendingCount(), "a still-shared file stays owed, it is not completed");
        assertEquals(ProductImageCleanupWorker.STILL_REFERENCED, lastErrorOfOnlyRow());
        assertTrue(Files.exists(file));

        // One of the two products is replaced. The other still points at the file.
        jdbcTemplate.update("DELETE FROM product_image_entity WHERE image_path = ?", "image_shared.png");
        makeNextAttemptDue();
        assertEquals(0, worker.cleanupDueFiles());
        assertTrue(Files.exists(file), "the file is still serving a product");

        jdbcTemplate.update("DELETE FROM product_image_entity");
        makeNextAttemptDue();

        assertEquals(1, worker.cleanupDueFiles());
        assertFalse(Files.exists(file));
    }

    /**
     * The check has to survive however the reference was spelled, and it has to
     * stay exact: a file whose name merely ends with the obsolete one is a
     * different file, and treating it as the same one would leave a real leak
     * forever under the guise of caution.
     */
    @Test
    void pathVariantsAreReconciledAndLookalikeNamesAreNotConfused() throws IOException {
        Path obsolete = writeUpload("image_x.png");
        Path lookalike = writeUpload("other_image_x.png");

        // Every spelling of the obsolete file counts as a reference to it.
        insertImageRow("image_x.png");
        insertImageRow("C:\\uploads\\image_x.png");
        insertImageRow("../image_x.png");
        insertImageRow("  image_x.png  ");
        queue.enqueueObsoleteReference("image_x.png");

        assertEquals(0, worker.cleanupDueFiles());
        assertTrue(Files.exists(obsolete));

        jdbcTemplate.update("DELETE FROM product_image_entity");
        insertImageRow("other_image_x.png");
        makeNextAttemptDue();

        assertEquals(1, worker.cleanupDueFiles());
        assertFalse(Files.exists(obsolete));
        assertTrue(Files.exists(lookalike),
                "a different file whose name merely ends with the obsolete one must not be read "
                        + "as a reference to it, and must never be deleted either");
    }

    // ------------------------------------------------------------------
    // Confinement: a traversal value can only ever name a file inside the
    // upload directory.
    // ------------------------------------------------------------------

    @Test
    void aTraversalValueCanOnlyEverDeleteInsideTheUploadDirectory() throws IOException {
        Path outside = OUTSIDE_DIR.resolve("image_x.png");
        Files.writeString(outside, "not an upload");
        Path inside = writeUpload("image_x.png");

        // The stored reference points outside; what it canonicalizes to is a
        // plain name inside the upload directory, and that is all it can reach.
        queue.enqueueObsoleteReference("../" + OUTSIDE_DIR.getFileName() + "/image_x.png");

        assertEquals(1, worker.cleanupDueFiles());
        assertTrue(Files.exists(outside), "cleanup must never delete a file outside the upload directory");
        assertFalse(Files.exists(inside));
    }

    // ------------------------------------------------------------------
    // Retention: completed rows go, pending rows never do.
    // ------------------------------------------------------------------

    @Test
    void retentionRemovesCompletedRowsAndNeverPendingOnes() throws IOException {
        writeUpload("image_done.png");
        queue.enqueueObsoleteReference("image_done.png");
        assertEquals(1, worker.cleanupDueFiles());

        // Still owed, and still on disk: this is the row retention must not touch.
        writeUpload("image_owed.png");
        queue.enqueueObsoleteReference("image_owed.png");
        jdbcTemplate.update("UPDATE product_image_cleanup_entity SET completed_at = ? "
                + "WHERE status = 'COMPLETED'", java.sql.Timestamp.valueOf(
                        LocalDateTime.now(clock).minusDays(40)));

        assertEquals(1, store.deleteCompletedBefore(LocalDateTime.now(clock).minusDays(30)));
        assertEquals(List.of("image_owed.png"), outstandingIdentities(),
                "a file that is still unreferenced and still on disk is never deleted by retention");
        assertTrue(Files.exists(UPLOAD_DIR.resolve("image_owed.png")));
    }

    @Test
    void backoffIsExponentialAndCapped() {
        // Built with its own budgets so the shape is visible: the shared worker
        // in this class runs with a one-second cap to keep retries fast.
        ProductImageCleanupWorker configured = new ProductImageCleanupWorker(store,
                productImageRepository, fileService, clock, LEASE_SECONDS, 3, 1L, 60L, 1L, 50);

        assertEquals(1, configured.backoffSeconds(1));
        assertEquals(2, configured.backoffSeconds(2));
        assertEquals(4, configured.backoffSeconds(3));
        assertEquals(60, configured.backoffSeconds(1_000_000),
                "an extreme attempt count must saturate instead of overflowing");
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    /**
     * A second worker built from the same collaborators, so a process gap is
     * real rather than simulated by a mock: nothing may be carried from the
     * first worker in memory, or a crashed-and-restarted instance could look
     * like it worked only because the earlier one had warmed something up.
     */
    private ProductImageCleanupWorker newWorker() {
        return new ProductImageCleanupWorker(store, productImageRepository, fileService, clock,
                LEASE_SECONDS, 3, 1L, 60L, 1L, 50);
    }

    private String enqueueAndReturnId(String fileIdentity) {
        queue.enqueueObsoleteReference(fileIdentity);
        return jdbcTemplate.queryForObject(
                "SELECT cleanup_id FROM product_image_cleanup_entity LIMIT 1", String.class);
    }

    private void insertImageRow(String storedPath) {
        jdbcTemplate.update("INSERT INTO product_image_entity (image_path, product_entity_id) "
                + "VALUES (?, NULL)", storedPath);
    }

    private Path writeUpload(String fileName) throws IOException {
        Path file = UPLOAD_DIR.resolve(fileName);
        Files.writeString(file, fileName);
        return file;
    }

    /**
     * Makes the deletion of {@code fileName} impossible in a way that is
     * portable and reproducible: a non-empty directory where a file is
     * expected. An empty directory would simply be removed, which is not a
     * failure at all.
     */
    private void blockDeletion(String fileName) throws IOException {
        Path blocked = UPLOAD_DIR.resolve(fileName);
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("occupant.txt"), "not empty");
    }

    private void unblockDeletion(String fileName) throws IOException {
        deleteRecursively(UPLOAD_DIR.resolve(fileName));
    }

    private boolean blockedAt(String fileName) {
        return Files.isDirectory(UPLOAD_DIR.resolve(fileName));
    }

    private void makeNextAttemptDue() {
        jdbcTemplate.update("UPDATE product_image_cleanup_entity SET next_attempt_at = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now(clock).minusSeconds(1)));
    }

    private void expireLease(String cleanupId) {
        jdbcTemplate.update("UPDATE product_image_cleanup_entity SET lease_expires_at = ? "
                        + "WHERE cleanup_id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now(clock).minusSeconds(1)), cleanupId);
    }

    private List<String> outstandingIdentities() {
        return jdbcTemplate.queryForList("SELECT file_identity FROM product_image_cleanup_entity "
                + "WHERE status IN ('PENDING', 'PROCESSING') ORDER BY sequence_no", String.class);
    }

    private int pendingCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM product_image_cleanup_entity "
                + "WHERE status = 'PENDING'", Integer.class);
    }

    private int completedCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM product_image_cleanup_entity "
                + "WHERE status = 'COMPLETED'", Integer.class);
    }

    private int attemptCountOfOnlyRow() {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM product_image_cleanup_entity LIMIT 1", Integer.class);
    }

    private String lastErrorOfOnlyRow() {
        return jdbcTemplate.queryForObject(
                "SELECT last_error FROM product_image_cleanup_entity LIMIT 1", String.class);
    }

    private String statusOfOnlyRow() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM product_image_cleanup_entity LIMIT 1", String.class);
    }

    /**
     * Asserts the database rejected the row, rather than that "something threw".
     *
     * <p>Accepting any {@code RuntimeException} would let a lost connection or a
     * missing table satisfy the assertion, which is exactly the kind of
     * environment failure that must fail a test rather than pass it. These
     * statements are narrow, so the SQL states are checked explicitly: 23514 is
     * {@code check_violation} (the identity and status constraints) and 23505 is
     * {@code unique_violation} (the outstanding-work index).
     */
    private static void assertRejectedBySchema(Runnable work) {
        try {
            work.run();
        } catch (DataIntegrityViolationException ex) {
            String sqlState = ex.getMostSpecificCause() instanceof PSQLException psql
                    ? psql.getSQLState()
                    : null;
            assertTrue("23514".equals(sqlState) || "23505".equals(sqlState),
                    "expected a check or unique violation, but the database reported " + sqlState);
            return;
        }
        throw new AssertionError("expected the schema to reject this row");
    }

    private static List<Path> listAll(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files.toList();
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            for (Path child : listAll(path)) {
                deleteRecursively(child);
            }
        }
        Files.deleteIfExists(path);
    }

    private static Path createUploadDir() {
        try {
            return Files.createTempDirectory("migros-cleanup-test");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
