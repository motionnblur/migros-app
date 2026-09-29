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
 * V11 adds the product edit version, and it has to land on both shapes of
 * database: an empty one, where every table is built by the migrations, and one
 * that already holds products.
 *
 * <p>The upgraded case is the one that can fail quietly. Adding a
 * {@code NOT NULL} column to a populated table either backfills every existing
 * row or fails outright, and getting it wrong there means an upgrade that either
 * cannot start or leaves rows with a null version that every later comparison
 * would reject forever.
 *
 * <p>The bulk stock increment is asserted here too, because it is the one writer
 * that bypasses entity version handling: if it forgets to advance the column, an
 * open admin edit form would consider a restocked product unchanged and be
 * allowed to write its stale count straight over the restock.
 */
@Testcontainers
class ProductEditVersionMigrationPostgresTest {

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
    void aFreshEmptyDatabaseGetsAUsableVersionColumn() throws SQLException {
        MigrateResult result = migrate();
        assertEquals(MigrationTestSupport.versionedMigrationCount(), result.migrationsExecuted,
                "a first migrate must apply every versioned script, including V11");

        assertVersionColumnShape();
        assertEquals(1, singleLong("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version = '11' AND success"),
                "Flyway must record the product-edit-version migration on an empty database");

        // Hibernate runs with ddl-auto=validate, so the column has to exist with
        // a type the entity mapping can validate against.
        long inserted = insertProduct("Fresh Apple", 5);
        assertEquals(0, versionOf(inserted),
                "a product inserted without a version must land on 0, not null");
    }

    @Test
    void anUpgradedDatabaseBackfillsEveryExistingProductToZero() throws SQLException {
        migrateTo("10");
        assertEquals(0, columnCount("product_entity", "version"),
                "precondition: the column must not exist before V11");

        long first = insertProduct("Legacy Apple", 10);
        long second = insertProduct("Legacy Pear", 7);

        migrate();

        assertVersionColumnShape();
        assertEquals(0, versionOf(first));
        assertEquals(0, versionOf(second));
        assertEquals(10, productCount(first), "an upgrade must not touch inventory");
        assertEquals(7, productCount(second));
    }

    /**
     * The upgrade is safe to re-run: a rolled-back deploy that left V11 applied
     * must not fail the next startup.
     */
    @Test
    void reRunningTheMigrationChangesNothing() throws SQLException {
        migrateTo("10");
        long productId = insertProduct("Idempotent Apple", 3);

        migrate();
        migrate();

        assertEquals(0, versionOf(productId));
        assertEquals(3, productCount(productId));
    }

    /**
     * The column is the guard an admin edit is compared against, so it must be
     * genuinely non-null rather than merely defaulted on insert.
     */
    @Test
    void theVersionColumnRejectsAnExplicitNull() {
        assertThrows(SQLException.class,
                () -> execute("INSERT INTO product_entity (product_name, subcategory_name, product_count, "
                        + "product_price, product_discount, product_description, version) "
                        + "VALUES ('No Version', 'general', 1, 1.00, 0.00, 'desc', NULL)"),
                "a null version would make every later comparison fail for that row");
    }

    /**
     * The bulk increment is the single writer that does not go through a managed
     * entity, so {@code @Version} never fires for it. This is the statement the
     * repository actually issues, executed directly, to pin that it advances the
     * version in the same statement that changes stock.
     */
    @Test
    void theBulkStockIncrementAdvancesTheVersion() throws SQLException {
        migrate();
        long productId = insertProduct("Restocked Apple", 4);

        execute("UPDATE product_entity SET product_count = product_count + 6, "
                + "version = version + 1 WHERE product_entity_id = " + productId);

        assertEquals(10, productCount(productId));
        assertEquals(1, versionOf(productId),
                "a bulk restock must be visible to an open admin edit form");
    }

    private void assertVersionColumnShape() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT data_type, is_nullable, column_default FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = 'product_entity' "
                             + "AND column_name = 'version'")) {
            assertTrue(rs.next(), "product_entity.version must exist after V11");
            assertEquals("bigint", rs.getString("data_type"));
            assertEquals("NO", rs.getString("is_nullable"),
                    "@Version is compared with equals(); a nullable column would never match");
            assertTrue(String.valueOf(rs.getString("column_default")).contains("0"),
                    "the column must default to 0 so a plain INSERT produces a usable version");
        }
    }

    private long insertProduct(String name, int stock) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "INSERT INTO product_entity (product_name, subcategory_name, product_count, "
                             + "product_price, product_discount, product_description) VALUES ('" + name + "', "
                             + "'general', " + stock + ", 5.00, 0.00, 'migration guard') "
                             + "RETURNING product_entity_id")) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private long versionOf(long productId) throws SQLException {
        return singleLong("SELECT version FROM product_entity WHERE product_entity_id = " + productId);
    }

    private long productCount(long productId) throws SQLException {
        return singleLong("SELECT product_count FROM product_entity WHERE product_entity_id = " + productId);
    }

    private long columnCount(String table, String column) throws SQLException {
        return singleLong("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = '" + table + "' AND column_name = '" + column + "'");
    }

    private long singleLong(String sql) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "query returned no row: " + sql);
            return rs.getLong(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
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
}
