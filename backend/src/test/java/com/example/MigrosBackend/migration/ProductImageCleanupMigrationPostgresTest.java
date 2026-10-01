package com.example.MigrosBackend.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V12 has to build the product-image cleanup queue on both shapes of database:
 * an empty one, where every table is created by the migrations, and one that
 * already holds products and image rows.
 *
 * <p>The upgraded case is the one that can fail quietly. Adding a table that
 * only a fresh database gets means the deployed instance silently never records
 * a cleanup obligation, and every obsolete file accumulates forever while the
 * queue reports itself empty.
 *
 * <p>The schema's own guarantees are asserted here rather than only in
 * application tests, because they are what a hand-written or migrated row has
 * to survive: a terminal status has to be rejected so no code path can park
 * pending work permanently, and an identity that is not a confined file name
 * has to be rejected so no path outside the upload directory can ever reach a
 * delete.
 */
@Testcontainers
class ProductImageCleanupMigrationPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @BeforeEach
    void resetSchema() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
    }

    @Test
    void aFreshEmptyDatabaseGetsTheCleanupQueue() throws SQLException {
        MigrateResult result = migrate();
        assertEquals(MigrationTestSupport.versionedMigrationCount(), result.migrationsExecuted,
                "a first migrate must apply every versioned script, including V12");

        assertQueueShape();
        assertEquals(1, singleLong("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version = '12' AND success"),
                "Flyway must record the cleanup-queue migration on an empty database");
    }

    @Test
    void anUpgradedDatabaseGetsTheCleanupQueueAlongsideItsExistingImages() throws SQLException {
        migrateTo("11");
        assertEquals(0, tableCount("product_image_cleanup_entity"),
                "precondition: the queue must not exist before V12");

        long productId = insertProduct("Legacy Apple");
        // A legacy row, in the shape earlier versions could leave behind: an
        // absolute path rather than a bare name.
        execute("INSERT INTO product_image_entity (image_path, product_entity_id) VALUES ('"
                + "/var/lib/migros/uploads/image_legacy.png', " + productId + ")");

        migrate();

        assertQueueShape();
        assertEquals(1, singleLong("SELECT count(*) FROM product_image_entity"),
                "the upgrade must not touch existing product images");
        assertEquals("/var/lib/migros/uploads/image_legacy.png", stringOf(
                        "SELECT image_path FROM product_image_entity"),
                "an upgrade must not rewrite how a stored reference is spelled");
    }

    /**
     * The upgrade is safe to re-run: a rolled-back deploy that left V12 applied
     * must not fail the next startup.
     */
    @Test
    void reRunningTheMigrationChangesNothing() throws SQLException {
        migrateTo("11");
        migrate();

        MigrateResult second = migrate();
        assertEquals(0, second.migrationsExecuted, "a second migrate must be a no-op");
        assertQueueShape();
    }

    /**
     * Outstanding work is deduplicated by canonical file identity, which is the
     * difference between one worker deleting a shared legacy file and two
     * workers racing for it. The ids differ deliberately: what is being
     * rejected has to be the identity collision, not a repeated primary key.
     */
    @Test
    void outstandingWorkIsDeduplicatedByFileIdentity() throws SQLException {
        migrate();

        execute(insertCleanup("id-one", "shared.png"));

        assertThrows(SQLException.class, () -> execute(insertCleanup("id-two", "shared.png")),
                "two outstanding obligations for the same file must collapse into one row");
        assertThrows(SQLException.class, () -> execute(insertCleanup("id-three", "shared.png")),
                "the deduplication has to hold for every later enqueue, not only the second");
        assertEquals(1, singleLong("SELECT count(*) FROM product_image_cleanup_entity"));
    }

    /**
     * A completed row must not block a later obligation for the same name: the
     * file it recorded has actually been removed, and the table is not a
     * permanent statement that the name was ever obsolete.
     */
    @Test
    void aCompletedRowDoesNotBlockAFreshObligationForTheSameFile() throws SQLException {
        migrate();

        execute(insertCleanup("id-one", "shared.png"));
        execute("UPDATE product_image_cleanup_entity SET status = 'COMPLETED', completed_at = now()");

        execute(insertCleanup("id-two", "shared.png"));
        assertEquals(2, singleLong("SELECT count(*) FROM product_image_cleanup_entity"));
    }

    /**
     * There is no terminal failure state, and the schema has to refuse one so
     * nothing can reintroduce it. A row parked terminally would abandon a file
     * that is still on disk and still unreferenced.
     */
    @Test
    void theQueueRejectsATerminalFailureState() {
        migrate();

        assertThrows(SQLException.class, () -> execute(insertCleanup("id-one", "given-up.png", "FAILED")));
        assertThrows(SQLException.class, () -> execute(insertCleanup("id-two", "unknown.png", "NOT_A_STATUS")));
    }

    /**
     * The identity is handed to a filesystem delete, so the schema refuses a
     * value that is not a plain file name confined to the upload directory. This
     * is defence in depth behind the application rule, not a replacement for
     * it: a row inserted by hand or by a future migration must not be able to
     * name {@code ../} or a drive path.
     */
    @Test
    void theQueueRejectsAnUnconfinedFileIdentity() {
        migrate();

        assertThrows(SQLException.class, () -> execute(insertCleanup("id-one", "../escape.png")));
        assertThrows(SQLException.class, () -> execute(insertCleanup("id-two", "nested/escape.png")));
        assertThrows(SQLException.class, () -> execute(insertCleanup("id-three", "nested\\escape.png")));
        assertThrows(SQLException.class, () -> execute(insertCleanup("id-four", "C:escape.png")));
        assertThrows(SQLException.class, () -> execute(insertCleanup("id-five", "..")));
        assertThrows(SQLException.class, () -> execute(insertCleanup("id-six", "")));
    }

    private void assertQueueShape() throws SQLException {
        try (Connection connection = openConnection()) {
            assertColumn(connection, "file_identity", "character varying", "NO");
            assertColumn(connection, "next_attempt_at", "timestamp without time zone", "NO");
            assertColumn(connection, "completed_at", "timestamp without time zone", "YES");
            assertConstraint(connection, "product_image_cleanup_entity", "chk_product_image_cleanup_status");
            assertConstraint(connection, "product_image_cleanup_entity", "chk_product_image_cleanup_identity");
            assertIndex(connection, "idx_product_image_cleanup_due");
            assertIndex(connection, "idx_product_image_cleanup_lease");
            assertIndex(connection, "idx_product_image_cleanup_completed");
            assertTrue(isPartialUniqueIndex(connection, "uq_product_image_cleanup_outstanding"),
                    "deduplication has to cover exactly the outstanding statuses, or a completed row "
                            + "would block a genuine later obligation for the same file");
        }
    }

    private void assertColumn(Connection connection, String column, String dataType, String nullable)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT data_type, is_nullable FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = 'product_image_cleanup_entity' "
                             + "AND column_name = '" + column + "'")) {
            assertTrue(rs.next(), "product_image_cleanup_entity." + column + " must exist after V12");
            assertEquals(dataType, rs.getString("data_type"));
            assertEquals(nullable, rs.getString("is_nullable"),
                    "unexpected nullability on product_image_cleanup_entity." + column);
        }
    }

    private String insertCleanup(String cleanupId, String fileIdentity) {
        return insertCleanup(cleanupId, fileIdentity, "PENDING");
    }

    private String insertCleanup(String cleanupId, String fileIdentity, String status) {
        return "INSERT INTO product_image_cleanup_entity "
                + "(cleanup_id, file_identity, status, attempt_count, next_attempt_at, created_at) "
                + "VALUES ('" + cleanupId + "', '" + fileIdentity + "', '"
                + status + "', 0, now(), now())";
    }

    /**
     * A row as it looked before V13, which is the state this class sets up: it
     * migrates to V11 and only then applies the cleanup-queue migration. Naming
     * {@code effective_price} here would fail with a confusing "column does not
     * exist" rather than a clear one.
     */
    private long insertProduct(String name) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "INSERT INTO product_entity (product_name, subcategory_name, product_count, "
                             + "product_price, product_discount, product_description) VALUES ('" + name + "', "
                             + "'general', 100, 5.00, 0.00, 'migration guard') "
                             + "RETURNING product_entity_id")) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    private MigrateResult migrate() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long singleLong(String sql) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "query returned no row: " + sql);
            return rs.getLong(1);
        }
    }

    private String stringOf(String sql) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "query returned no row: " + sql);
            return rs.getString(1);
        }
    }

    private long tableCount(String table) throws SQLException {
        return singleLong("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = '" + table + "'");
    }

    private void assertConstraint(Connection connection, String table, String constraintName)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.table_constraints "
                             + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                             + "AND constraint_name = '" + constraintName + "'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "missing constraint " + constraintName);
        }
    }

    private void assertIndex(Connection connection, String indexName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM pg_indexes "
                     + "WHERE schemaname = 'public' AND indexname = '" + indexName + "'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "missing index " + indexName);
        }
    }

    private boolean isPartialUniqueIndex(Connection connection, String indexName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT i.indisunique, i.indpred IS NOT NULL "
                     + "FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                     + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                     + "WHERE n.nspname = 'public' AND c.relname = '" + indexName + "'")) {
            assertTrue(rs.next(), "missing index " + indexName);
            return rs.getBoolean(1) && rs.getBoolean(2);
        }
    }
}
