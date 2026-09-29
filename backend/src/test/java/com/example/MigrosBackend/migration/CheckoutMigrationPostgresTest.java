package com.example.MigrosBackend.migration;

import org.flywaydb.core.Flyway;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class CheckoutMigrationPostgresTest {

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
    void migrationCreatesCheckoutSchemaWithTypesConstraintsAndIndexes() throws SQLException {
        createBaseSchema();
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO user_entity (user_mail) VALUES ('keeper@migros.com')");
        }

        migrate();

        try (Connection connection = openConnection()) {
            assertColumn(connection, "checkout_entity", "total_amount", "numeric", 19, 2);
            assertColumn(connection, "checkout_entity", "amount_minor", "bigint", null, null);
            assertColumn(connection, "checkout_entity", "currency", "character varying", null, null);
            assertColumn(connection, "checkout_item_entity", "unit_price", "numeric", 19, 2);
            assertColumn(connection, "checkout_item_entity", "line_total", "numeric", 19, 2);

            assertConstraint(connection, "checkout_entity", "chk_checkout_status");
            assertConstraint(connection, "checkout_entity", "chk_checkout_currency");
            assertConstraint(connection, "checkout_entity", "chk_checkout_total_positive");
            assertConstraint(connection, "checkout_entity", "uq_checkout_order_group");
            assertConstraint(connection, "checkout_item_entity", "chk_checkout_item_quantity_positive");
            assertConstraint(connection, "checkout_item_entity", "uq_checkout_item_product");

            assertForeignKey(connection, "checkout_entity", "user_entity");
            assertForeignKey(connection, "checkout_item_entity", "checkout_entity");
            assertForeignKey(connection, "checkout_item_entity", "product_entity");

            assertIndex(connection, "uq_checkout_live_per_user");
            assertIndex(connection, "idx_checkout_user_status");
            assertIndex(connection, "idx_checkout_item_checkout");
        }

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM user_entity")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "existing rows must be retained by the migration");
        }
    }

    @Test
    void oneLiveCheckoutPerUserIsEnforcedByPartialUniqueIndex() throws SQLException {
        createBaseSchema();
        migrate();

        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID terminalId = UUID.randomUUID();

        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO user_entity (user_entity_id, user_mail) VALUES (1, 'a@migros.com')");
            statement.execute(checkoutInsert(firstId, 1, "PREPARED"));
        }

        SQLException duplicateLive = assertThrows(SQLException.class, () -> {
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.execute(checkoutInsert(secondId, 1, "PAYMENT_PROCESSING"));
            }
        });
        assertTrue(duplicateLive.getMessage().toLowerCase().contains("unique"),
                "expected a unique violation, got: " + duplicateLive.getMessage());

        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute(checkoutInsert(terminalId, 1, "CANCELLED"));
        }
    }

    @Test
    void moneyAndQuantityChecksAreEnforcedAtTheDatabase() throws SQLException {
        createBaseSchema();
        migrate();

        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO user_entity (user_entity_id, user_mail) VALUES (1, 'a@migros.com')");
            statement.execute("INSERT INTO product_entity (product_entity_id, product_name) VALUES (1, 'Apple')");
            statement.execute(checkoutInsert(UUID.randomUUID(), 1, "PREPARED"));
        }

        UUID checkoutId;
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT checkout_id FROM checkout_entity LIMIT 1")) {
            assertTrue(rs.next());
            checkoutId = rs.getObject(1, UUID.class);
        }

        assertThrows(SQLException.class, () -> execute("INSERT INTO checkout_item_entity "
                + "(checkout_id, product_entity_id, product_name, quantity, unit_price, line_total) VALUES "
                + "('" + checkoutId + "', 1, 'Apple', 0, 1.00, 0.00)"));
        assertThrows(SQLException.class, () -> execute("INSERT INTO checkout_item_entity "
                + "(checkout_id, product_entity_id, product_name, quantity, unit_price, line_total) VALUES "
                + "('" + checkoutId + "', 1, 'Apple', -1, 1.00, 1.00)"));
        assertThrows(SQLException.class, () -> execute("INSERT INTO checkout_entity "
                + "(checkout_id, user_entity_id, status, total_amount, amount_minor, currency, created_at, "
                + "expires_at, updated_at, version) VALUES "
                + "('" + UUID.randomUUID() + "', 1, 'CANCELLED', 1.00, 100, 'usd', now(), now() + interval '1 hour', now(), 0)"));
    }

    @Test
    void migrationIsIdempotentAndRecordsVersionTwo() throws SQLException {
        createBaseSchema();
        MigrateResult first = migrate();
        assertEquals(MigrationTestSupport.versionedMigrationCount(), first.migrationsExecuted);

        MigrateResult second = migrate();
        assertEquals(0, second.migrationsExecuted);

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT success FROM flyway_schema_history WHERE version = '2'")) {
            assertTrue(rs.next());
            assertTrue(rs.getBoolean("success"));
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String checkoutInsert(UUID id, long userId, String status) {
        return "INSERT INTO checkout_entity (checkout_id, user_entity_id, status, total_amount, amount_minor, "
                + "currency, created_at, expires_at, updated_at, version) VALUES "
                + "('" + id + "', " + userId + ", '" + status + "', 10.00, 1000, 'try', now(), "
                + "now() + interval '1 hour', now(), 0)";
    }

    private void createBaseSchema() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE user_entity ("
                    + "user_entity_id BIGSERIAL PRIMARY KEY, "
                    + "user_mail VARCHAR(255))");
            statement.execute("CREATE TABLE product_entity ("
                    + "product_entity_id BIGSERIAL PRIMARY KEY, "
                    + "product_name VARCHAR(255) NOT NULL)");
        }
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

    private void assertColumn(Connection connection, String table, String column, String dataType,
                              Integer precision, Integer scale) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT data_type, numeric_precision, numeric_scale FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                             + "AND column_name = '" + column + "'")) {
            assertTrue(rs.next(), "missing column " + table + "." + column);
            assertEquals(dataType, rs.getString("data_type"));
            if (precision != null) {
                assertEquals(precision.intValue(), rs.getInt("numeric_precision"));
                assertEquals(scale.intValue(), rs.getInt("numeric_scale"));
            }
        }
    }

    private void assertConstraint(Connection connection, String table, String constraintName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.table_constraints "
                             + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                             + "AND constraint_name = '" + constraintName + "'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "missing constraint " + constraintName);
        }
    }

    private void assertForeignKey(Connection connection, String table, String referencedTable) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.table_constraints tc "
                             + "JOIN information_schema.constraint_column_usage ccu "
                             + "  ON tc.constraint_name = ccu.constraint_name "
                             + "WHERE tc.table_schema = 'public' AND tc.table_name = '" + table + "' "
                             + "AND tc.constraint_type = 'FOREIGN KEY' "
                             + "AND ccu.table_name = '" + referencedTable + "'")) {
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) >= 1, "missing foreign key " + table + " -> " + referencedTable);
        }
    }

    private void assertIndex(Connection connection, String indexName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' "
                             + "AND indexname = '" + indexName + "'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "missing index " + indexName);
        }
    }
}
