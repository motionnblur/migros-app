package com.example.MigrosBackend.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class EmptyDatabaseStartupPostgresTest {

    private static final String USER_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String ADMIN_SECRET = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jwt.user-secret", () -> USER_SECRET);
        registry.add("jwt.admin-secret", () -> ADMIN_SECRET);
        registry.add("support.internal.key", () -> "integration-test-internal-key");
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void emptyDatabaseStartsAndFlywayAppliesMigration() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            assertNumeric19Scale2(statement, "product_entity", "product_price");
            assertNumeric19Scale2(statement, "product_entity", "product_discount");
            assertNumeric19Scale2(statement, "order_entity", "price");
            assertNumeric19Scale2(statement, "order_entity", "total_price");

            try (ResultSet rs = statement.executeQuery(
                    "SELECT success FROM flyway_schema_history WHERE version = '1'")) {
                assertTrue(rs.next(), "Flyway must record the money-columns migration on an empty database");
                assertTrue(rs.getBoolean("success"));
            }

            try (ResultSet rs = statement.executeQuery(
                    "SELECT success FROM flyway_schema_history WHERE version = '2'")) {
                assertTrue(rs.next(), "Flyway must record the checkout-snapshot migration on an empty database");
                assertTrue(rs.getBoolean("success"));
            }

            try (ResultSet rs = statement.executeQuery(
                    "SELECT success FROM flyway_schema_history WHERE version = '3'")) {
                assertTrue(rs.next(), "Flyway must record the payment-attempt migration on an empty database");
                assertTrue(rs.getBoolean("success"));
            }

            try (ResultSet rs = statement.executeQuery(
                    "SELECT success FROM flyway_schema_history WHERE version = '4'")) {
                assertTrue(rs.next(), "Flyway must record the webhook-inbox migration on an empty database");
                assertTrue(rs.getBoolean("success"));
            }

            try (ResultSet rs = statement.executeQuery(
                    "SELECT success FROM flyway_schema_history WHERE version = '5'")) {
                assertTrue(rs.next(), "Flyway must record the payment-schema reconciliation on an empty database");
                assertTrue(rs.getBoolean("success"));
            }

            assertTableExists(statement, "checkout_entity");
            assertTableExists(statement, "checkout_item_entity");
            assertTableExists(statement, "payment_attempt_entity");
            assertTableExists(statement, "stripe_event_entity");
            assertNumeric19Scale2(statement, "checkout_entity", "total_amount");
            assertNumeric19Scale2(statement, "checkout_item_entity", "unit_price");
            assertIndexExists(statement, "uq_checkout_live_per_user");
            assertIndexExists(statement, "idx_payment_attempt_status_updated");

            // V11: the product edit version must exist on a schema built entirely
            // by the migrations, not only on an upgraded one, or the entity's
            // @Version mapping fails ddl-auto=validate at startup.
            assertBigIntNotNullDefaultZero(statement, "product_entity", "version");
            // V13: likewise for the materialized effective price. NOT NULL matters
            // beyond validation too - a null there is a row no price filter
            // compares and no ordering can place.
            assertNumeric19Scale2NotNull(statement, "product_entity", "effective_price");
            assertTrue(versionedMigrationsAllApplied(),
                    "every versioned script must be recorded after a first migrate");
        }
    }

    private boolean versionedMigrationsAllApplied() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM flyway_schema_history "
                             + "WHERE version IS NOT NULL AND success")) {
            assertTrue(rs.next());
            return rs.getInt(1) == MigrationTestSupport.versionedMigrationCount();
        }
    }

    @Test
    void freshSchemaExposesCanonicalPaymentConstraints() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            // Canonical check constraints declared by the payment manifest.
            assertConstraint(statement, "checkout_entity", "chk_checkout_total_positive");
            assertConstraint(statement, "checkout_entity", "chk_checkout_amount_minor_positive");
            assertConstraint(statement, "checkout_entity", "chk_checkout_currency");
            assertConstraint(statement, "checkout_entity", "chk_checkout_status");
            assertConstraint(statement, "checkout_item_entity", "chk_checkout_item_quantity_positive");
            assertConstraint(statement, "checkout_item_entity", "chk_checkout_item_unit_price_non_negative");
            assertConstraint(statement, "checkout_item_entity", "chk_checkout_item_line_total_non_negative");
            assertConstraint(statement, "payment_attempt_entity", "chk_payment_attempt_amount_positive");
            assertConstraint(statement, "payment_attempt_entity", "chk_payment_attempt_currency");
            assertConstraint(statement, "payment_attempt_entity", "chk_payment_attempt_status");
            assertConstraint(statement, "stripe_event_entity", "chk_stripe_event_status");

            // Canonical uniqueness: checkout/order, checkout/item, attempt/checkout,
            // idempotency key and provider charge id.
            assertConstraint(statement, "checkout_entity", "uq_checkout_order_group");
            assertConstraint(statement, "checkout_item_entity", "uq_checkout_item_product");
            assertConstraint(statement, "payment_attempt_entity", "uq_payment_attempt_checkout");
            assertConstraint(statement, "payment_attempt_entity", "uq_payment_attempt_idempotency");
            assertConstraint(statement, "payment_attempt_entity", "uq_payment_attempt_charge");

            // Canonical foreign keys with stable names.
            assertForeignKey(statement, "checkout_entity", "fk_checkout_user");
            assertForeignKey(statement, "checkout_item_entity", "fk_checkout_item_checkout");
            assertForeignKey(statement, "checkout_item_entity", "fk_checkout_item_product");
            assertForeignKey(statement, "payment_attempt_entity", "fk_payment_attempt_checkout");

            // Recovery/status lookup and live-checkout indexes.
            assertIndexExists(statement, "uq_checkout_live_per_user");
            assertIndexExists(statement, "idx_checkout_user_status");
            assertIndexExists(statement, "idx_checkout_expires_at");
            assertIndexExists(statement, "idx_checkout_item_checkout");
            assertIndexExists(statement, "idx_payment_attempt_status_updated");
            assertIndexExists(statement, "idx_stripe_event_recovery");
            assertIndexExists(statement, "idx_stripe_event_lease");
        }
    }

    @Test
    void freshSchemaRejectsInvalidCheckoutData() throws SQLException {
        long userId = createUser("checkout-guard@migros.com");

        // Non-positive totals, wrong currency and unknown statuses must fail in PostgreSQL.
        assertThrows(SQLException.class, () -> insertCheckout(
                userId, "PREPARED", "0.00", 0L, "try"));
        assertThrows(SQLException.class, () -> insertCheckout(
                userId, "PREPARED", "-5.00", -500L, "try"));
        assertThrows(SQLException.class, () -> insertCheckout(
                userId, "PREPARED", "10.00", 1000L, "usd"));
        assertThrows(SQLException.class, () -> insertCheckout(
                userId, "PREPARED", "10.00", 1000L, "TRY"));
        assertThrows(SQLException.class, () -> insertCheckout(
                userId, "NOT_A_STATE", "10.00", 1000L, "try"));

        // Invalid item quantities and negative money must fail.
        java.util.UUID checkoutId = insertCheckout(userId, "CANCELLED", "10.00", 1000L, "try");
        long productId = createProduct("Guard Apple");
        assertThrows(SQLException.class, () -> insertCheckoutItem(
                checkoutId, productId, "Guard Apple", 0, "1.00", "0.00"));
        assertThrows(SQLException.class, () -> insertCheckoutItem(
                checkoutId, productId, "Guard Apple", -2, "1.00", "1.00"));
        assertThrows(SQLException.class, () -> insertCheckoutItem(
                checkoutId, productId, "Guard Apple", 1, "-1.00", "1.00"));
        assertThrows(SQLException.class, () -> insertCheckoutItem(
                checkoutId, productId, "Guard Apple", 1, "1.00", "-1.00"));

        // Checkout-to-order linkage is unique.
        execute("UPDATE checkout_entity SET order_group_entity_id = 9001 WHERE checkout_id = '" + checkoutId + "'");
        assertThrows(SQLException.class, () -> {
            java.util.UUID other = java.util.UUID.randomUUID();
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO checkout_entity (checkout_id, user_entity_id, status, "
                        + "total_amount, amount_minor, currency, created_at, expires_at, updated_at, version, "
                        + "order_group_entity_id) VALUES ('" + other + "', " + userId + ", 'EXPIRED', 10.00, 1000, "
                        + "'try', now(), now() + interval '1 hour', now(), 0, 9001)");
            }
        });
    }

    @Test
    void freshSchemaRejectsInvalidPaymentAttemptData() throws SQLException {
        long userId = createUser("attempt-guard@migros.com");
        java.util.UUID checkoutId = insertCheckout(userId, "CANCELLED", "10.00", 1000L, "try");
        java.util.UUID otherCheckout = insertCheckout(userId, "EXPIRED", "10.00", 1000L, "try");

        assertThrows(SQLException.class, () -> insertAttempt(
                checkoutId, "guard-key-amount", 0L, "try", "PROCESSING"));
        assertThrows(SQLException.class, () -> insertAttempt(
                checkoutId, "guard-key-currency", 1000L, "usd", "PROCESSING"));
        assertThrows(SQLException.class, () -> insertAttempt(
                checkoutId, "guard-key-status", 1000L, "try", "NOT_A_STATE"));

        // One attempt per checkout, one row per idempotency key and per provider charge id.
        insertAttempt(checkoutId, "guard-key-ok", 1000L, "try", "PROCESSING",
                "ch_guard_1");
        assertThrows(SQLException.class, () -> insertAttempt(
                checkoutId, "guard-key-other", 1000L, "try", "PROCESSING"));
        assertThrows(SQLException.class, () -> insertAttempt(
                otherCheckout, "guard-key-ok", 1000L, "try", "PROCESSING"));
        assertThrows(SQLException.class, () -> insertAttempt(
                otherCheckout, "guard-key-charge", 1000L, "try", "PROCESSING", "ch_guard_1"));
    }

    @Test
    void freshSchemaAllowsManyTerminalCheckoutsButOneLiveCheckout() throws SQLException {
        long userId = createUser("live-guard@migros.com");
        insertCheckout(userId, "PREPARED", "10.00", 1000L, "try");
        assertThrows(SQLException.class, () -> insertCheckout(
                userId, "PAYMENT_PROCESSING", "10.00", 1000L, "try"));

        insertCheckout(userId, "PAID", "10.00", 1000L, "try");
        insertCheckout(userId, "CONSUMED", "10.00", 1000L, "try");
        insertCheckout(userId, "CANCELLED", "10.00", 1000L, "try");
        insertCheckout(userId, "EXPIRED", "10.00", 1000L, "try");
    }

    @Test
    void freshSchemaCascadesCheckoutItemDeletes() throws SQLException {
        long userId = createUser("cascade-guard@migros.com");
        java.util.UUID checkoutId = insertCheckout(userId, "CANCELLED", "10.00", 1000L, "try");
        long productId = createProduct("Cascade Apple");
        insertCheckoutItem(checkoutId, productId, "Cascade Apple", 2, "5.00", "10.00");

        execute("DELETE FROM checkout_entity WHERE checkout_id = '" + checkoutId + "'");

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM checkout_item_entity WHERE checkout_id = '" + checkoutId + "'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "checkout items must cascade with their checkout");
        }
    }

@Test
    void freshSchemaAppliesWebhookInboxDefaults() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO stripe_event_entity (event_id, event_type, received_at) "
                    + "VALUES ('evt_fresh_default', 'charge.succeeded', now())");
            try (ResultSet rs = statement.executeQuery(
                    "SELECT status, attempt_count, payload_hash FROM stripe_event_entity "
                            + "WHERE event_id = 'evt_fresh_default'")) {
                assertTrue(rs.next());
                assertEquals("RECEIVED", rs.getString("status"));
                assertEquals(0, rs.getInt("attempt_count"));
                assertEquals("", rs.getString("payload_hash"));
            }
            assertThrows(SQLException.class, () -> {
                try (Connection connection2 = dataSource.getConnection();
                     Statement statement2 = connection2.createStatement()) {
                    statement2.execute("INSERT INTO stripe_event_entity "
                            + "(event_id, event_type, received_at, status) "
                            + "VALUES ('evt_fresh_bad', 'charge.succeeded', now(), 'NOT_A_STATE')");
                }
            });
        }
    }

    /**
     * V14's two package columns, on a schema built entirely by the migrations.
     *
     * <p>Nullable with no default on purpose, so the assertions are about their
     * absence rather than their presence: a default would turn "the administrator
     * did not enter a package size" into "this product contains one unit" for every
     * product the migrations create. The declared types still have to match the
     * entity mapping exactly, or {@code ddl-auto=validate} refuses to start.
     */
    @Test
    void freshSchemaExposesTheOptionalPackageColumns() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertNullableNumeric(statement, "product_entity", "package_amount", 12, 3);
            assertNullableVarchar(statement, "product_entity", "package_unit");

            assertConstraint(statement, "product_entity", "chk_product_package_paired");
            assertConstraint(statement, "product_entity", "chk_product_package_positive");
            assertConstraint(statement, "product_entity", "chk_product_package_whole_adet");

            // A product created without package metadata - which is what every
            // row created before V14 looks like - has to be insertable, and has to
            // report no size afterwards.
            long productId = createProduct("No Package Size");
            try (ResultSet rs = statement.executeQuery(
                    "SELECT package_amount, package_unit FROM product_entity "
                            + "WHERE product_entity_id = " + productId)) {
                assertTrue(rs.next());
                assertEquals(null, rs.getObject("package_amount"),
                        "a product created without package metadata must not be given one");
                assertEquals(null, rs.getObject("package_unit"));
            }
        }
    }

    private void assertNullableNumeric(Statement statement, String table, String column,
                                       int precision, int scale) throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT numeric_precision, numeric_scale, is_nullable, column_default "
                        + "FROM information_schema.columns WHERE table_schema = 'public' "
                        + "AND table_name = '" + table + "' AND column_name = '" + column + "'")) {
            assertTrue(rs.next(), table + "." + column + " must exist on a fresh schema");
            assertEquals(precision, rs.getInt("numeric_precision"),
                    table + "." + column + " must match the entity mapping or startup fails");
            assertEquals(scale, rs.getInt("numeric_scale"));
            assertEquals("YES", rs.getString("is_nullable"),
                    "absence is the ordinary state for package metadata");
            assertEquals(null, rs.getString("column_default"),
                    "a default would invent a package size for every product that omits one");
        }
    }

    private void assertNullableVarchar(Statement statement, String table, String column)
            throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT character_maximum_length, is_nullable, column_default "
                        + "FROM information_schema.columns WHERE table_schema = 'public' "
                        + "AND table_name = '" + table + "' AND column_name = '" + column + "'")) {
            assertTrue(rs.next(), table + "." + column + " must exist on a fresh schema");
            assertTrue(rs.getInt("character_maximum_length") >= 4,
                    table + "." + column + " must hold the widest accepted unit token");
            assertEquals("YES", rs.getString("is_nullable"));
            assertEquals(null, rs.getString("column_default"));
        }
    }

    private void assertIndexExists(Statement statement, String indexName) throws SQLException {        try (ResultSet rs = statement.executeQuery(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' "
                        + "AND indexname = '" + indexName + "'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "index " + indexName + " must exist after startup");
        }
    }

    private void assertConstraint(Statement statement, String tableName, String constraintName)
            throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = '" + tableName + "' "
                        + "AND constraint_name = '" + constraintName + "'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "constraint " + constraintName + " must exist on " + tableName);
        }
    }

    private void assertForeignKey(Statement statement, String tableName, String constraintName)
            throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'public' AND table_name = '" + tableName + "' "
                        + "AND constraint_type = 'FOREIGN KEY' "
                        + "AND constraint_name = '" + constraintName + "'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "foreign key " + constraintName + " must exist on " + tableName);
        }
    }

    private long createUser(String mail) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "INSERT INTO user_entity (user_mail) VALUES ('" + mail + "') "
                             + "RETURNING user_entity_id")) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private long createProduct(String name) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "INSERT INTO product_entity (product_name, subcategory_name, product_count, "
                             + "product_price, product_discount, effective_price, product_description) "
                             + "VALUES ('" + name + "', "
                             + "'general', 100, 5.00, 0.00, 5.00, 'guard product') "
                             + "RETURNING product_entity_id")) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private java.util.UUID insertCheckout(long userId, String status, String total, long minor, String currency)
            throws SQLException {
        java.util.UUID id = java.util.UUID.randomUUID();
        execute("INSERT INTO checkout_entity (checkout_id, user_entity_id, status, total_amount, "
                + "amount_minor, currency, created_at, expires_at, updated_at, version) VALUES "
                + "('" + id + "', " + userId + ", '" + status + "', " + total + ", " + minor + ", '"
                + currency + "', now(), now() + interval '1 hour', now(), 0)");
        return id;
    }

    private void insertCheckoutItem(java.util.UUID checkoutId, long productId, String productName,
                                    int quantity, String unitPrice, String lineTotal) throws SQLException {
        execute("INSERT INTO checkout_item_entity (checkout_id, product_entity_id, product_name, "
                + "quantity, unit_price, line_total) VALUES ('" + checkoutId + "', " + productId + ", '"
                + productName + "', " + quantity + ", " + unitPrice + ", " + lineTotal + ")");
    }

    private void insertAttempt(java.util.UUID checkoutId, String idempotencyKey, long amountMinor,
                               String currency, String status) throws SQLException {
        insertAttempt(checkoutId, idempotencyKey, amountMinor, currency, status, null);
    }

    private void insertAttempt(java.util.UUID checkoutId, String idempotencyKey, long amountMinor,
                               String currency, String status, String chargeId) throws SQLException {
        execute("INSERT INTO payment_attempt_entity (attempt_id, checkout_id, idempotency_key, "
                + "amount_minor, currency, status, stripe_charge_id, created_at, updated_at, version) VALUES "
                + "('" + java.util.UUID.randomUUID() + "', '" + checkoutId + "', '" + idempotencyKey + "', "
                + amountMinor + ", '" + currency + "', '" + status + "', "
                + (chargeId == null ? "NULL" : "'" + chargeId + "'") + ", now(), now(), 0)");
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void assertTableExists(Statement statement, String tableName) throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = '" + tableName + "'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "table " + tableName + " must exist after startup");
        }
    }

    private void assertNumeric19Scale2(Statement statement, String tableName, String columnName)
            throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT data_type, numeric_precision, numeric_scale FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = '" + tableName + "' "
                        + "AND column_name = '" + columnName + "'")) {
            assertTrue(rs.next(), "column " + tableName + "." + columnName + " must exist");
            assertEquals("numeric", rs.getString("data_type"));
            assertEquals(19, rs.getInt("numeric_precision"));
            assertEquals(2, rs.getInt("numeric_scale"));
        }
    }

    private void assertBigIntNotNullDefaultZero(Statement statement, String tableName, String columnName)
            throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT data_type, is_nullable, column_default FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = '" + tableName + "' "
                        + "AND column_name = '" + columnName + "'")) {
            assertTrue(rs.next(), "column " + tableName + "." + columnName + " must exist");
            assertEquals("bigint", rs.getString("data_type"));
            assertEquals("NO", rs.getString("is_nullable"));
            assertTrue(String.valueOf(rs.getString("column_default")).contains("0"),
                    "column " + tableName + "." + columnName + " must default to 0");
        }
    }

    /**
     * A {@code NUMERIC(19, 2)} column that also refuses null.
     *
     * <p>Separate from {@link #assertNumeric19Scale2} because the nullability is a
     * different property with a different consequence: the money columns may be
     * nullable for a legacy database, but a null effective price is a row the
     * catalogue search cannot compare or order at all, which is the one thing this
     * column exists to make possible.
     */
    private void assertNumeric19Scale2NotNull(Statement statement, String tableName, String columnName)
            throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT data_type, numeric_precision, numeric_scale, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = '" + tableName + "' "
                        + "AND column_name = '" + columnName + "'")) {
            assertTrue(rs.next(), "column " + tableName + "." + columnName + " must exist");
            assertEquals("numeric", rs.getString("data_type"));
            assertEquals(19, rs.getInt("numeric_precision"));
            assertEquals(2, rs.getInt("numeric_scale"));
            assertEquals("NO", rs.getString("is_nullable"));
        }
    }
}
