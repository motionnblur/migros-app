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
class PaymentAttemptMigrationPostgresTest {

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
    void migrationCreatesPaymentAttemptSchemaWithTypesConstraintsAndIndexes() throws SQLException {
        createBaseSchema();
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO user_entity (user_mail) VALUES ('keeper@migros.com')");
        }

        migrate();

        try (Connection connection = openConnection()) {
            assertColumn(connection, "payment_attempt_entity", "amount_minor", "bigint", null, null);
            assertColumn(connection, "payment_attempt_entity", "currency", "character varying", null, null);
            assertColumn(connection, "payment_attempt_entity", "refund_id", "character varying", null, null);
            assertColumn(connection, "payment_attempt_entity", "lease_expires_at",
                    "timestamp without time zone", null, null);
            assertColumn(connection, "stripe_event_entity", "received_at",
                    "timestamp without time zone", null, null);

            assertConstraint(connection, "payment_attempt_entity", "uq_payment_attempt_checkout");
            assertConstraint(connection, "payment_attempt_entity", "uq_payment_attempt_idempotency");
            assertConstraint(connection, "payment_attempt_entity", "uq_payment_attempt_charge");
            assertConstraint(connection, "payment_attempt_entity", "chk_payment_attempt_amount_positive");
            assertConstraint(connection, "payment_attempt_entity", "chk_payment_attempt_status");

            assertForeignKey(connection, "payment_attempt_entity", "checkout_entity");
            assertIndex(connection, "idx_payment_attempt_status_updated");
        }

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM user_entity")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "existing rows must be retained by the migration");
        }
    }

    @Test
    void oneAttemptPerCheckoutAndOneIdempotencyKeyAreEnforced() throws SQLException {
        createBaseSchema();
        migrate();

        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO user_entity (user_entity_id, user_mail) VALUES (1, 'a@migros.com')");
            statement.execute(checkoutInsert(UUID.randomUUID(), 1));
        }

        UUID checkoutId = firstCheckoutId();
        execute(attemptInsert(UUID.randomUUID(), checkoutId, "checkout:" + checkoutId + ":charge-v1"));

        UUID otherCheckout = insertSecondCheckout();
        SQLException duplicateCheckout = assertThrows(SQLException.class,
                () -> execute(attemptInsert(UUID.randomUUID(), checkoutId, "checkout:unique:charge-v1")));
        assertTrue(duplicateCheckout.getMessage().toLowerCase().contains("unique"));

        SQLException duplicateKey = assertThrows(SQLException.class,
                () -> execute(attemptInsert(UUID.randomUUID(), otherCheckout,
                        "checkout:" + checkoutId + ":charge-v1")));
        assertTrue(duplicateKey.getMessage().toLowerCase().contains("unique"));
    }

    @Test
    void invalidAmountAndStatusAreRejectedByTheDatabase() throws SQLException {
        createBaseSchema();
        migrate();
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO user_entity (user_entity_id, user_mail) VALUES (1, 'a@migros.com')");
            statement.execute(checkoutInsert(UUID.randomUUID(), 1));
        }
        UUID checkoutId = firstCheckoutId();

        assertThrows(SQLException.class,
                () -> execute(attemptInsert(UUID.randomUUID(), checkoutId, "key-a", 0L, "PROCESSING")));
        assertThrows(SQLException.class,
                () -> execute(attemptInsert(UUID.randomUUID(), checkoutId, "key-b", 1000L, "NOT_A_STATE")));
    }

    @Test
    void migrationCreatesInboxLifecycleColumnsConstraintsAndIndexes() throws SQLException {
        createBaseSchema();
        migrate();

        try (Connection connection = openConnection()) {
            assertColumn(connection, "stripe_event_entity", "status",
                    "character varying", null, null);
            assertColumn(connection, "stripe_event_entity", "payload_hash",
                    "character varying", null, null);
            assertColumn(connection, "stripe_event_entity", "attempt_count",
                    "integer", null, null);
            assertColumn(connection, "stripe_event_entity", "last_error",
                    "character varying", null, null);
            assertColumn(connection, "stripe_event_entity", "lease_owner",
                    "character varying", null, null);
            assertColumn(connection, "stripe_event_entity", "lease_expires_at",
                    "timestamp without time zone", null, null);
            assertColumn(connection, "stripe_event_entity", "processing_started_at",
                    "timestamp without time zone", null, null);
            assertColumn(connection, "stripe_event_entity", "next_attempt_at",
                    "timestamp without time zone", null, null);

            assertConstraint(connection, "stripe_event_entity", "chk_stripe_event_status");
            assertIndex(connection, "idx_stripe_event_recovery");
            assertIndex(connection, "idx_stripe_event_lease");
        }
    }

    @Test
    void existingV3RowsAreBackfilledIntoInboxStates() throws SQLException {
        createBaseSchema();
        migrateTo("3");

        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO stripe_event_entity (event_id, event_type, received_at, processed_at) "
                    + "VALUES ('evt_done', 'charge.succeeded', now() - interval '2 hours', now() - interval '1 hour')");
            statement.execute("INSERT INTO stripe_event_entity (event_id, event_type, received_at) "
                    + "VALUES ('evt_pending', 'charge.succeeded', now() - interval '1 hour')");
        }

        migrate();

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT event_id, status, attempt_count, payload_hash, next_attempt_at "
                             + "FROM stripe_event_entity ORDER BY event_id")) {
            assertTrue(rs.next());
            assertEquals("evt_done", rs.getString("event_id"));
            assertEquals("PROCESSED", rs.getString("status"));
            assertTrue(rs.next());
            assertEquals("evt_pending", rs.getString("event_id"));
            assertEquals("RECEIVED", rs.getString("status"));
            assertEquals(0, rs.getInt("attempt_count"));
            assertEquals("", rs.getString("payload_hash"));
            assertTrue(rs.getTimestamp("next_attempt_at") != null,
                    "unprocessed legacy rows must become immediately due");
        }
    }

    @Test
    void legacyMinimalInsertsKeepWorkingThroughColumnDefaults() throws SQLException {
        createBaseSchema();
        migrate();

        execute("INSERT INTO stripe_event_entity (event_id, event_type, received_at) "
                + "VALUES ('evt_legacy', 'charge.failed', now())");

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT status, attempt_count, payload_hash FROM stripe_event_entity "
                             + "WHERE event_id = 'evt_legacy'")) {
            assertTrue(rs.next());
            assertEquals("RECEIVED", rs.getString("status"));
            assertEquals(0, rs.getInt("attempt_count"));
            assertEquals("", rs.getString("payload_hash"));
        }
    }

    @Test
    void migrationIsIdempotentAndRecordsVersionFour() throws SQLException {
        createBaseSchema();
        MigrateResult first = migrate();
        assertEquals(MigrationTestSupport.versionedMigrationCount(), first.migrationsExecuted,
                "every versioned script must run on a first migrate");

        MigrateResult second = migrate();
        assertEquals(0, second.migrationsExecuted);

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT success FROM flyway_schema_history WHERE version = '4'")) {
            assertTrue(rs.next());
            assertTrue(rs.getBoolean("success"));
        }
    }

    @Test
    void migrationBuildsCompletePaymentSchemaFromEmptyDatabase() throws SQLException {
        // V5 owns completion: even with no base schema at all, Flyway alone must
        // build the base tables plus fully constrained payment tables (no
        // Hibernate runtime DDL required).
        MigrateResult result = migrate();
        assertEquals(MigrationTestSupport.versionedMigrationCount(), result.migrationsExecuted);

        try (Connection connection = openConnection()) {
            assertColumn(connection, "checkout_entity", "total_amount", "numeric", 19, 2);
            assertColumn(connection, "payment_attempt_entity", "amount_minor", "bigint", null, null);
            assertConstraint(connection, "checkout_entity", "chk_checkout_total_positive");
            assertConstraint(connection, "checkout_entity", "chk_checkout_currency");
            assertConstraint(connection, "checkout_entity", "uq_checkout_order_group");
            assertConstraint(connection, "payment_attempt_entity", "uq_payment_attempt_checkout");
            assertConstraint(connection, "payment_attempt_entity", "uq_payment_attempt_idempotency");
            assertConstraint(connection, "payment_attempt_entity", "chk_payment_attempt_amount_positive");
            assertConstraint(connection, "stripe_event_entity", "chk_stripe_event_status");
            assertIndex(connection, "uq_checkout_live_per_user");
            assertIndex(connection, "idx_checkout_user_status");
            assertIndex(connection, "idx_stripe_event_recovery");
        }
    }

    private UUID firstCheckoutId() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT checkout_id FROM checkout_entity LIMIT 1")) {
            assertTrue(rs.next());
            return rs.getObject(1, UUID.class);
        }
    }

    private UUID insertSecondCheckout() throws SQLException {
        UUID id = UUID.randomUUID();
        execute("INSERT INTO checkout_entity (checkout_id, user_entity_id, status, total_amount, amount_minor, "
                + "currency, created_at, expires_at, updated_at, version) VALUES "
                + "('" + id + "', 1, 'CANCELLED', 10.00, 1000, 'try', now(), "
                + "now() + interval '1 hour', now(), 0)");
        return id;
    }

    private String checkoutInsert(UUID id, long userId) {
        return "INSERT INTO checkout_entity (checkout_id, user_entity_id, status, total_amount, amount_minor, "
                + "currency, created_at, expires_at, updated_at, version) VALUES "
                + "('" + id + "', " + userId + ", 'PREPARED', 10.00, 1000, 'try', now(), "
                + "now() + interval '1 hour', now(), 0)";
    }

    private String attemptInsert(UUID attemptId, UUID checkoutId, String idempotencyKey) {
        return attemptInsert(attemptId, checkoutId, idempotencyKey, 1000L, "PROCESSING");
    }

    private String attemptInsert(UUID attemptId, UUID checkoutId, String idempotencyKey,
                                 long amountMinor, String status) {
        return "INSERT INTO payment_attempt_entity (attempt_id, checkout_id, idempotency_key, amount_minor, "
                + "currency, status, created_at, updated_at, version) VALUES "
                + "('" + attemptId + "', '" + checkoutId + "', '" + idempotencyKey + "', "
                + amountMinor + ", 'try', '" + status + "', now(), now(), 0)";
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
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

    private MigrateResult migrateTo(String version) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target(version)
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
