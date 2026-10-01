package com.example.MigrosBackend.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that a fresh database and upgraded legacy databases converge to the
 * same payment-critical schema, and that PostgreSQL rejects invalid
 * checkout/payment data on every path.
 *
 * <p>Paths under test:
 * <ul>
 *   <li>fresh: completely empty database, Flyway V1..Vn only;</li>
 *   <li>legacy-pre-v1: representative pre-migration schema (REAL money columns,
 *       populated base tables, no payment tables);</li>
 *   <li>legacy-hibernate: pre-migration base plus payment tables as Hibernate
 *       {@code ddl-auto=update} would have created them (auto-named foreign
 *       keys, enum-only checks, no currency/amount checks, no inbox defaults
 *       or recovery indexes);</li>
 *   <li>v3-stopover: schema migrated only up to V3 with live rows, then
 *       brought current.</li>
 * </ul>
 */
@Testcontainers
class PaymentSchemaEquivalencePostgresTest {

    private static final String[] PAYMENT_TABLES = {
            "checkout_entity", "checkout_item_entity", "payment_attempt_entity", "stripe_event_entity"
    };

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
    void freshAndLegacyPreV1ConvergeToSamePaymentSchema() throws Exception {
        SortedMap<String, SortedSet<String>> fresh = migrateFreshAndSnapshot();
        writeEvidence("fresh", fresh);

        resetSchema();
        createLegacyPreV1Base();
        seedLegacyMoneyRows();
        MigrateResult result = migrate();
        assertTrue(result.migrationsExecuted >= 5,
                "legacy database must run V1..V5, ran " + result.migrationsExecuted);

        SortedMap<String, SortedSet<String>> upgraded = snapshotPaymentSchema();
        writeEvidence("upgraded-pre-v1", upgraded);
        assertEquals(fresh, upgraded, "fresh and upgraded schemas must be equivalent");

        assertMoneyConvertedWithRounding();
        assertLegacyRowsRetained();
    }

    @Test
    void hibernateShapedPaymentTablesAreNormalizedToSameSchema() throws Exception {
        SortedMap<String, SortedSet<String>> fresh = migrateFreshAndSnapshot();

        resetSchema();
        createLegacyPreV1Base();
        seedLegacyMoneyRows();
        createHibernateShapedPaymentTables();
        seedHibernateShapedPaymentRows();
        migrate();

        SortedMap<String, SortedSet<String>> upgraded = snapshotPaymentSchema();
        writeEvidence("upgraded-hibernate", upgraded);
        assertEquals(fresh, upgraded,
                "Hibernate-created payment tables must be normalized to the canonical schema");

        assertMoneyConvertedWithRounding();
        assertHibernateShapedPaymentRowsRetained();
    }

    @Test
    void databaseStoppedAtV3ReceivesReconciliationWithoutDataLoss() throws SQLException {
        SortedMap<String, SortedSet<String>> fresh = migrateFreshAndSnapshot();
        resetSchema();

        createFullNumericBase();
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO user_entity (user_entity_id, user_mail) VALUES (7, 'v3@migros.com')");
            statement.execute("INSERT INTO product_entity (product_entity_id, product_name, subcategory_name, "
                    + "product_count, product_price, product_discount, product_description) VALUES "
                    + "(7, 'V3 Apple', 'general', 10, 5.00, 0.00, 'v3 product')");
        }
        migrateTo("3");

        UUID checkoutId = UUID.randomUUID();
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO checkout_entity (checkout_id, user_entity_id, status, total_amount, "
                    + "amount_minor, currency, created_at, expires_at, updated_at, version) VALUES "
                    + "('" + checkoutId + "', 7, 'PAYMENT_PROCESSING', 10.00, 1000, 'try', now(), "
                    + "now() + interval '1 hour', now(), 0)");
            statement.execute("INSERT INTO payment_attempt_entity (attempt_id, checkout_id, idempotency_key, "
                    + "amount_minor, currency, status, created_at, updated_at, version) VALUES "
                    + "('" + UUID.randomUUID() + "', '" + checkoutId + "', 'checkout:" + checkoutId
                    + ":charge-v1', 1000, 'try', 'PROCESSING', now(), now(), 0)");
            statement.execute("INSERT INTO stripe_event_entity (event_id, event_type, received_at, processed_at) "
                    + "VALUES ('evt_v3_done', 'charge.succeeded', now() - interval '2 hours', "
                    + "now() - interval '1 hour')");
            statement.execute("INSERT INTO stripe_event_entity (event_id, event_type, received_at) "
                    + "VALUES ('evt_v3_pending', 'charge.succeeded', now() - interval '1 hour')");
        }

        MigrateResult result = migrate();
        assertTrue(result.migrationsExecuted >= 1, "forward reconciliation must run on a V3 database");

        SortedMap<String, SortedSet<String>> reconciled = snapshotPaymentSchema();
        assertEquals(fresh, reconciled, "V3-stopover database must converge to the canonical schema");

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM payment_attempt_entity WHERE checkout_id = '" + checkoutId + "'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "V3 payment attempt rows must be retained");
        }
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT event_id, status FROM stripe_event_entity ORDER BY event_id")) {
            assertTrue(rs.next());
            assertEquals("evt_v3_done", rs.getString("event_id"));
            assertEquals("PROCESSED", rs.getString("status"));
            assertTrue(rs.next());
            assertEquals("evt_v3_pending", rs.getString("event_id"));
            assertEquals("RECEIVED", rs.getString("status"));
        }
    }

    @Test
    void invalidDataIsRejectedOnFreshAndUpgradedDatabases() throws SQLException {
        migrate();
        assertInvalidCheckoutInsertsRejected();
        assertInvalidAttemptInsertsRejected();
        assertLiveCheckoutRuleEnforced();

        resetSchema();
        createLegacyPreV1Base();
        seedLegacyMoneyRows();
        createHibernateShapedPaymentTables();
        migrate();
        assertInvalidCheckoutInsertsRejected();
        assertInvalidAttemptInsertsRejected();
        assertLiveCheckoutRuleEnforced();
    }

    @Test
    void remigrateIsIdempotent() throws SQLException {
        createLegacyPreV1Base();
        MigrateResult first = migrate();
        assertTrue(first.migrationsExecuted >= 5);
        MigrateResult second = migrate();
        assertEquals(0, second.migrationsExecuted, "re-migrating must be a no-op");

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "no failed migration rows may exist");
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Representative pre-V1 base schema: Hibernate-shaped tables with legacy REAL money columns. */
    private void createLegacyPreV1Base() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE admin_entity (admin_entity_id BIGSERIAL PRIMARY KEY, "
                    + "admin_name VARCHAR(255), admin_password VARCHAR(255))");
            statement.execute("CREATE TABLE category_entity (category_entity_id BIGSERIAL PRIMARY KEY, "
                    + "category_id INTEGER NOT NULL, category_name VARCHAR(255))");
            statement.execute("CREATE TABLE user_entity (user_entity_id BIGSERIAL PRIMARY KEY, "
                    + "banned BOOLEAN, products_ids_in_cart BIGINT[], user_address VARCHAR(255), "
                    + "user_address2 VARCHAR(255), user_country VARCHAR(255), user_last_name VARCHAR(255), "
                    + "user_mail VARCHAR(255), user_name VARCHAR(255), user_password VARCHAR(255), "
                    + "user_postal_code VARCHAR(255), user_town VARCHAR(255))");
            statement.execute("CREATE TABLE product_entity (product_entity_id BIGSERIAL PRIMARY KEY, "
                    + "product_count INTEGER NOT NULL, product_description VARCHAR(255) NOT NULL, "
                    + "product_discount REAL NOT NULL, product_name VARCHAR(255) NOT NULL, "
                    + "product_price REAL NOT NULL, subcategory_name VARCHAR(255) NOT NULL, "
                    + "admin_entity_id BIGINT, category_entity_id BIGINT)");
            statement.execute("CREATE TABLE order_group_entity (order_group_entity_id BIGSERIAL PRIMARY KEY, "
                    + "created_at TIMESTAMP WITHOUT TIME ZONE, status VARCHAR(255), user_id BIGINT, "
                    + "user_entity_id BIGINT)");
            statement.execute("CREATE TABLE order_entity (order_entity_id BIGSERIAL PRIMARY KEY, "
                    + "count INTEGER, item_id BIGINT, price REAL, status VARCHAR(255), total_price REAL, "
                    + "user_id BIGINT, order_group_entity_id BIGINT, user_entity_id BIGINT)");
            statement.execute("CREATE TABLE support_messages (id BIGSERIAL PRIMARY KEY, "
                    + "created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL, edited_at TIMESTAMP WITHOUT TIME ZONE, "
                    + "external_message_id VARCHAR(255) UNIQUE, message VARCHAR(2000) NOT NULL, "
                    + "sender VARCHAR(255) NOT NULL, user_mail VARCHAR(255) NOT NULL)");
            statement.execute("CREATE TABLE pending_signup_entity (token VARCHAR(64) PRIMARY KEY, "
                    + "expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL, user_mail VARCHAR(255) NOT NULL, "
                    + "user_password VARCHAR(255) NOT NULL)");
            statement.execute("CREATE TABLE product_image_entity ("
                    + "product_image_entity_id BIGSERIAL PRIMARY KEY, image_path VARCHAR(255), "
                    + "product_entity_id BIGINT)");
            statement.execute("CREATE TABLE product_description_entity ("
                    + "product_description_entity_id BIGSERIAL PRIMARY KEY, "
                    + "description_tab_content VARCHAR(255), description_tab_name VARCHAR(255), "
                    + "product_entity_id BIGINT)");
        }
    }

    private void seedLegacyMoneyRows() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO user_entity (user_entity_id, user_mail) VALUES "
                    + "(1, 'legacy@migros.com')");
            statement.execute("INSERT INTO product_entity (product_entity_id, product_name, subcategory_name, "
                    + "product_count, product_price, product_discount, product_description) VALUES "
                    + "(1, 'plain', 'general', 10, 50.25, 10.0, 'legacy product'), "
                    + "(2, 'round-up', 'general', 10, 10.125, 0.0, 'legacy product')");
            statement.execute("INSERT INTO order_entity (order_entity_id, price, total_price, user_entity_id) "
                    + "VALUES (1, 25.25, 75.75, 1)");
            // Explicit legacy ids must not collide with later auto-generated ids.
            statement.execute("SELECT setval('user_entity_user_entity_id_seq', "
                    + "(SELECT COALESCE(max(user_entity_id), 0) FROM user_entity))");
            statement.execute("SELECT setval('product_entity_product_entity_id_seq', "
                    + "(SELECT COALESCE(max(product_entity_id), 0) FROM product_entity))");
            statement.execute("SELECT setval('order_entity_order_entity_id_seq', "
                    + "(SELECT COALESCE(max(order_entity_id), 0) FROM order_entity))");
        }
    }

    /**
     * Payment tables shaped the way Hibernate {@code ddl-auto=update} creates
     * them on a database that never saw V2/V3: auto-named foreign keys, only
     * enum checks, canonical unique names where the JPA mappings declare them,
     * a generated unique name where the mapping uses {@code @Column(unique=true)},
     * no amount/currency checks, no inbox defaults and no recovery indexes.
     */
    private void createHibernateShapedPaymentTables() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE checkout_entity (checkout_id UUID PRIMARY KEY, "
                    + "user_entity_id BIGINT NOT NULL REFERENCES user_entity (user_entity_id), "
                    + "status VARCHAR(32) NOT NULL, total_amount NUMERIC(19, 2) NOT NULL, "
                    + "amount_minor BIGINT NOT NULL, currency VARCHAR(3) NOT NULL, "
                    + "created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL, "
                    + "expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL, "
                    + "updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL, version BIGINT NOT NULL, "
                    + "order_group_entity_id BIGINT, stripe_charge_id VARCHAR(255), "
                    + "CONSTRAINT uk_legacy_checkout_order_group UNIQUE (order_group_entity_id), "
                    + "CONSTRAINT checkout_entity_status_check CHECK (status IN ('PREPARED', "
                    + "'PAYMENT_PROCESSING', 'PAID', 'CONSUMED', 'CANCELLED', 'EXPIRED')))");
            statement.execute("CREATE TABLE checkout_item_entity ("
                    + "checkout_item_entity_id BIGSERIAL PRIMARY KEY, "
                    + "checkout_id UUID NOT NULL REFERENCES checkout_entity (checkout_id), "
                    + "product_entity_id BIGINT NOT NULL REFERENCES product_entity (product_entity_id), "
                    + "product_name VARCHAR(255) NOT NULL, quantity INTEGER NOT NULL, "
                    + "unit_price NUMERIC(19, 2) NOT NULL, line_total NUMERIC(19, 2) NOT NULL, "
                    + "CONSTRAINT uq_checkout_item_product UNIQUE (checkout_id, product_entity_id))");
            statement.execute("CREATE TABLE payment_attempt_entity (attempt_id UUID PRIMARY KEY, "
                    + "checkout_id UUID NOT NULL REFERENCES checkout_entity (checkout_id), "
                    + "idempotency_key VARCHAR(200) NOT NULL, amount_minor BIGINT NOT NULL, "
                    + "currency VARCHAR(3) NOT NULL, status VARCHAR(32) NOT NULL, "
                    + "stripe_charge_id VARCHAR(255), provider_status VARCHAR(64), error_code VARCHAR(64), "
                    + "refund_id VARCHAR(255), lease_owner VARCHAR(64), "
                    + "lease_expires_at TIMESTAMP WITHOUT TIME ZONE, "
                    + "created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL, "
                    + "updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL, version BIGINT NOT NULL, "
                    + "CONSTRAINT uq_payment_attempt_checkout UNIQUE (checkout_id), "
                    + "CONSTRAINT uq_payment_attempt_idempotency UNIQUE (idempotency_key), "
                    + "CONSTRAINT uq_payment_attempt_charge UNIQUE (stripe_charge_id), "
                    + "CONSTRAINT payment_attempt_entity_status_check CHECK (status IN ('CREATED', "
                    + "'PROCESSING', 'CHARGE_SUCCEEDED', 'ORDER_FINALIZED', 'FAILED_FINAL', "
                    + "'REFUND_PENDING', 'REFUNDED', 'MANUAL_REVIEW')))");
            statement.execute("CREATE TABLE stripe_event_entity (event_id VARCHAR(255) PRIMARY KEY, "
                    + "event_type VARCHAR(100) NOT NULL, payload TEXT, payload_hash VARCHAR(64) NOT NULL, "
                    + "status VARCHAR(16) NOT NULL, attempt_count INTEGER NOT NULL, "
                    + "last_error VARCHAR(64), lease_owner VARCHAR(64), "
                    + "lease_expires_at TIMESTAMP WITHOUT TIME ZONE, "
                    + "processing_started_at TIMESTAMP WITHOUT TIME ZONE, "
                    + "next_attempt_at TIMESTAMP WITHOUT TIME ZONE, "
                    + "received_at TIMESTAMP WITHOUT TIME ZONE NOT NULL, "
                    + "processed_at TIMESTAMP WITHOUT TIME ZONE, "
                    + "CONSTRAINT stripe_event_entity_status_check CHECK (status IN ('RECEIVED', "
                    + "'PROCESSING', 'PROCESSED', 'FAILED', 'MANUAL_REVIEW')))");
        }
    }

    private void seedHibernateShapedPaymentRows() throws SQLException {
        UUID checkoutId = UUID.randomUUID();
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO checkout_entity (checkout_id, user_entity_id, status, total_amount, "
                    + "amount_minor, currency, created_at, expires_at, updated_at, version) VALUES "
                    + "('" + checkoutId + "', 1, 'PAID', 10.00, 1000, 'try', now(), "
                    + "now() + interval '1 hour', now(), 0)");
            statement.execute("INSERT INTO checkout_item_entity (checkout_id, product_entity_id, product_name, "
                    + "quantity, unit_price, line_total) VALUES ('" + checkoutId + "', 1, 'plain', 2, 5.00, 10.00)");
            statement.execute("INSERT INTO payment_attempt_entity (attempt_id, checkout_id, idempotency_key, "
                    + "amount_minor, currency, status, stripe_charge_id, created_at, updated_at, version) VALUES "
                    + "('" + UUID.randomUUID() + "', '" + checkoutId + "', 'checkout:" + checkoutId
                    + ":charge-v1', 1000, 'try', 'ORDER_FINALIZED', 'ch_legacy_1', now(), now(), 0)");
            statement.execute("INSERT INTO stripe_event_entity (event_id, event_type, payload_hash, status, "
                    + "attempt_count, received_at, processed_at) VALUES ('evt_legacy_done', 'charge.succeeded', "
                    + "'abc', 'PROCESSED', 1, now() - interval '2 hours', now() - interval '1 hour')");
            statement.execute("INSERT INTO stripe_event_entity (event_id, event_type, payload_hash, status, "
                    + "attempt_count, received_at) VALUES ('evt_legacy_pending', 'charge.succeeded', "
                    + "'def', 'RECEIVED', 0, now() - interval '1 hour')");
        }
    }

    /** Full Hibernate-shaped base with NUMERIC money columns (for the V3-stopover path). */
    private void createFullNumericBase() throws SQLException {
        createLegacyPreV1Base();
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE product_entity ALTER COLUMN product_price TYPE NUMERIC(19, 2) "
                    + "USING round(product_price::numeric, 2)");
            statement.execute("ALTER TABLE product_entity ALTER COLUMN product_discount TYPE NUMERIC(19, 2) "
                    + "USING round(product_discount::numeric, 2)");
            statement.execute("ALTER TABLE order_entity ALTER COLUMN price TYPE NUMERIC(19, 2) "
                    + "USING round(price::numeric, 2)");
            statement.execute("ALTER TABLE order_entity ALTER COLUMN total_price TYPE NUMERIC(19, 2) "
                    + "USING round(total_price::numeric, 2)");
        }
    }

    // ------------------------------------------------------------------
    // Snapshot and comparison
    // ------------------------------------------------------------------

    private SortedMap<String, SortedSet<String>> migrateFreshAndSnapshot() throws SQLException {
        migrate();
        return snapshotPaymentSchema();
    }

    private SortedMap<String, SortedSet<String>> snapshotPaymentSchema() throws SQLException {
        SortedMap<String, SortedSet<String>> snapshot = new TreeMap<>();
        try (Connection connection = openConnection()) {
            for (String table : PAYMENT_TABLES) {
                SortedSet<String> entries = new TreeSet<>();
                entries.addAll(snapshotColumns(connection, table));
                entries.addAll(snapshotConstraints(connection, table));
                entries.addAll(snapshotIndexes(connection, table));
                snapshot.put(table, entries);
            }
            SortedSet<String> money = new TreeSet<>();
            money.addAll(snapshotColumns(connection, "product_entity"));
            money.addAll(snapshotColumns(connection, "order_entity"));
            snapshot.put("money_columns", money);
        }
        return snapshot;
    }

    private SortedSet<String> snapshotColumns(Connection connection, String table) throws SQLException {
        SortedSet<String> out = new TreeSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT column_name, data_type, COALESCE(character_maximum_length::text, ''), "
                             + "COALESCE(numeric_precision::text, ''), COALESCE(numeric_scale::text, ''), "
                             + "is_nullable, COALESCE(column_default, '') "
                             + "FROM information_schema.columns WHERE table_schema = 'public' "
                             + "AND table_name = '" + table + "' ORDER BY ordinal_position")) {
            while (rs.next()) {
                out.add("COL|" + rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|"
                        + rs.getString(4) + "|" + rs.getString(5) + "|" + rs.getString(6) + "|"
                        + normalize(rs.getString(7)));
            }
        }
        return out;
    }

    private SortedSet<String> snapshotConstraints(Connection connection, String table) throws SQLException {
        SortedSet<String> out = new TreeSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT c.conname, c.contype, "
                             + "(SELECT string_agg(a.attname, ',' ORDER BY u.ord) FROM "
                             + "unnest(c.conkey) WITH ORDINALITY AS u(attnum, ord) "
                             + "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = u.attnum), "
                             + "(SELECT cl.relname FROM pg_class cl WHERE cl.oid = c.confrelid), "
                             + "c.confdeltype, pg_get_constraintdef(c.oid) "
                             + "FROM pg_constraint c WHERE c.conrelid = '" + table + "'::regclass "
                             + "AND c.connamespace = 'public'::regnamespace ORDER BY 1")) {
            while (rs.next()) {
                String cols = rs.getString(3) == null ? "" : rs.getString(3);
                String ref = rs.getString(4) == null ? "" : rs.getString(4);
                String del = rs.getString(5) == null ? "" : rs.getString(5);
                if (rs.getString(2).equals("c")) {
                    out.add("CON|" + rs.getString(1) + "|c|" + cols + "|" + normalize(rs.getString(6)));
                } else if (rs.getString(2).equals("f")) {
                    out.add("CON|" + rs.getString(1) + "|f|" + cols + "|" + ref + "|" + del);
                } else {
                    out.add("CON|" + rs.getString(1) + "|" + rs.getString(2) + "|" + cols);
                }
            }
        }
        return out;
    }

    private SortedSet<String> snapshotIndexes(Connection connection, String table) throws SQLException {
        SortedSet<String> out = new TreeSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public' "
                             + "AND tablename = '" + table + "' ORDER BY 1")) {
            while (rs.next()) {
                out.add("IDX|" + rs.getString(1) + "|" + normalize(rs.getString(2)));
            }
        }
        return out;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private void writeEvidence(String name, SortedMap<String, SortedSet<String>> snapshot) throws Exception {
        Path dir = Path.of("target", "payment-schema-evidence");
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, SortedSet<String>> entry : snapshot.entrySet()) {
            sb.append("## ").append(entry.getKey()).append("\n");
            for (String line : entry.getValue()) {
                sb.append(line).append("\n");
            }
        }
        Files.writeString(dir.resolve(name + ".txt"), sb.toString());
    }

    // ------------------------------------------------------------------
    // Assertions shared by fresh and upgraded paths
    // ------------------------------------------------------------------

    private void assertInvalidCheckoutInsertsRejected() throws SQLException {
        long userId = insertUser("matrix-checkout@migros.com");
        long productId = insertProduct("Matrix Apple");

        // Invalid totals, currencies and statuses.
        assertThrows(SQLException.class,
                () -> execute(validCheckoutSql(userId, "PREPARED").replace("10.00, 1000", "0.00, 0")));
        assertThrows(SQLException.class,
                () -> execute(validCheckoutSql(userId, "PREPARED").replace("'try'", "'usd'")));
        assertThrows(SQLException.class, () -> execute(validCheckoutSql(userId, "BOGUS")));

        UUID checkoutId = insertValidCheckout(userId, "CANCELLED");

        // Invalid item quantities and negative money.
        assertThrows(SQLException.class, () -> execute("INSERT INTO checkout_item_entity (checkout_id, "
                + "product_entity_id, product_name, quantity, unit_price, line_total) VALUES ('" + checkoutId
                + "', " + productId + ", 'Matrix Apple', 0, 1.00, 0.00)"));
        assertThrows(SQLException.class, () -> execute("INSERT INTO checkout_item_entity (checkout_id, "
                + "product_entity_id, product_name, quantity, unit_price, line_total) VALUES ('" + checkoutId
                + "', " + productId + ", 'Matrix Apple', 1, -1.00, 1.00)"));

        // Checkout-to-order uniqueness.
        execute("UPDATE checkout_entity SET order_group_entity_id = 4242 WHERE checkout_id = '" + checkoutId
                + "'");
        assertThrows(SQLException.class,
                () -> execute(validCheckoutSql(userId, "EXPIRED").replace(", 0, NULL)", ", 0, 4242)")));
    }

    private void assertInvalidAttemptInsertsRejected() throws SQLException {
        long userId = insertUser("matrix-attempt@migros.com");
        UUID checkoutId = insertValidCheckout(userId, "CANCELLED");
        UUID otherCheckout = insertValidCheckout(userId, "EXPIRED");

        assertThrows(SQLException.class,
                () -> execute(validAttemptSql(checkoutId, "matrix-a").replace(", 1000,", ", 0,")));
        assertThrows(SQLException.class,
                () -> execute(validAttemptSql(checkoutId, "matrix-b").replace("'try'", "'usd'")));
        assertThrows(SQLException.class,
                () -> execute(validAttemptSql(checkoutId, "matrix-c").replace("'PROCESSING'", "'BOGUS'")));

        execute(validAttemptSql(checkoutId, "matrix-ok", "ch_matrix_1"));
        // Duplicate checkout, duplicate idempotency key, duplicate provider charge id.
        assertThrows(SQLException.class, () -> execute(validAttemptSql(checkoutId, "matrix-other")));
        assertThrows(SQLException.class, () -> execute(validAttemptSql(otherCheckout, "matrix-ok")));
        assertThrows(SQLException.class,
                () -> execute(validAttemptSql(otherCheckout, "matrix-charge", "ch_matrix_1")));
    }

    private void assertLiveCheckoutRuleEnforced() throws SQLException {
        long userId = insertUser("matrix-live@migros.com");
        execute(validCheckoutSql(userId, "PREPARED"));
        assertThrows(SQLException.class, () -> execute(validCheckoutSql(userId, "PAYMENT_PROCESSING")));
        execute(validCheckoutSql(userId, "PAID"));
        execute(validCheckoutSql(userId, "CANCELLED"));
    }

    private void assertMoneyConvertedWithRounding() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT product_name, product_price FROM product_entity ORDER BY product_entity_id")) {
            assertTrue(rs.next());
            assertEquals("plain", rs.getString(1));
            assertEquals(0, new java.math.BigDecimal("50.25").compareTo(rs.getBigDecimal(2)));
            assertTrue(rs.next());
            assertEquals("round-up", rs.getString(1));
            assertEquals(0, new java.math.BigDecimal("10.13").compareTo(rs.getBigDecimal(2)));
        }
    }

    private void assertLegacyRowsRetained() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM user_entity")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "legacy user rows must be retained");
        }
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM order_entity")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "legacy order rows must be retained");
        }
    }

    private void assertHibernateShapedPaymentRowsRetained() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM checkout_item_entity")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "pre-existing checkout items must be retained");
        }
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT status, next_attempt_at FROM stripe_event_entity "
                             + "WHERE event_id = 'evt_legacy_pending'")) {
            assertTrue(rs.next());
            assertEquals("RECEIVED", rs.getString("status"));
            assertTrue(rs.getTimestamp("next_attempt_at") != null,
                    "unprocessed inbox rows must become due");
        }
    }

    // ------------------------------------------------------------------
    // SQL helpers
    // ------------------------------------------------------------------

    private long insertUser(String mail) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "INSERT INTO user_entity (user_mail) VALUES ('" + mail + "') "
                             + "RETURNING user_entity_id")) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private long insertProduct(String name) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "INSERT INTO product_entity (product_name, subcategory_name, product_count, "
                             + "product_price, product_discount, effective_price, product_description) "
                             + "VALUES ('" + name
                             + "', 'general', 100, 5.00, 0.00, 5.00, 'matrix product') "
                             + "RETURNING product_entity_id")) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private String validCheckoutSql(long userId, String status) {
        return "INSERT INTO checkout_entity (checkout_id, user_entity_id, status, total_amount, "
                + "amount_minor, currency, created_at, expires_at, updated_at, version, "
                + "order_group_entity_id) VALUES ('" + UUID.randomUUID() + "', " + userId + ", '" + status
                + "', 10.00, 1000, 'try', now(), now() + interval '1 hour', now(), 0, NULL)";
    }

    private UUID insertValidCheckout(long userId, String status) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("INSERT INTO checkout_entity (checkout_id, user_entity_id, status, total_amount, "
                + "amount_minor, currency, created_at, expires_at, updated_at, version) VALUES ('" + id
                + "', " + userId + ", '" + status + "', 10.00, 1000, 'try', now(), "
                + "now() + interval '1 hour', now(), 0)");
        return id;
    }

    private String validAttemptSql(UUID checkoutId, String key) {
        return validAttemptSql(checkoutId, key, null);
    }

    private String validAttemptSql(UUID checkoutId, String key, String chargeId) {
        return "INSERT INTO payment_attempt_entity (attempt_id, checkout_id, idempotency_key, amount_minor, "
                + "currency, status, stripe_charge_id, created_at, updated_at, version) VALUES "
                + "('" + UUID.randomUUID() + "', '" + checkoutId + "', '" + key + "', 1000, 'try', "
                + "'PROCESSING', " + (chargeId == null ? "NULL" : "'" + chargeId + "'") + ", now(), now(), 0)";
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
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
}
