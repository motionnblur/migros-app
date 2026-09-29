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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V6 (pending-token purpose) and V7 (support outbox) must upgrade an existing,
 * populated schema, and must also build a usable schema from nothing.
 */
@Testcontainers
class SupportSchemaMigrationPostgresTest {

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
    void migratesAnExistingSchemaThatAlreadyHasPendingTokens() throws SQLException {
        // The pre-V6 shape: the table exists, and it holds tokens issued before
        // their purpose was recorded.
        createLegacySchemaWithPendingTokens();

        MigrateResult result = migrate();

        assertEquals(MigrationTestSupport.versionedMigrationCount(), result.migrationsExecuted);
        try (Connection connection = openConnection()) {
            assertEquals(0, countRows(connection, "pending_signup_entity"),
                    "a purpose-less token must be removed, not carried over with a guessed purpose");
            assertColumn(connection, "pending_signup_entity", "token_purpose", "character varying", 32L, null);
            assertTrue(isNotNullColumn(connection, "pending_signup_entity", "token_purpose"),
                    "the purpose column must be NOT NULL so a write cannot omit it");
            assertFalse(hasColumnDefault(connection, "pending_signup_entity", "token_purpose"),
                    "a DEFAULT would let a purpose-less insert silently acquire a purpose, "
                            + "which is exactly the guess this migration refuses to make");
            assertConstraint(connection, "pending_signup_entity", "chk_pending_token_purpose");
            assertTrue(indexExists(connection, "idx_pending_signup_mail_purpose"));
        }
    }

    @Test
    void migrationIsIdempotentAndRejectsAnUnknownPurpose() throws SQLException {
        createLegacySchemaWithPendingTokens();
        migrate();

        MigrateResult second = migrate();
        assertEquals(0, second.migrationsExecuted, "a second migrate must be a no-op");

        try (Connection connection = openConnection()) {
            assertThrows(SQLException.class, () -> execute(connection,
                    "INSERT INTO pending_signup_entity (token, user_mail, user_password, expires_at, token_purpose) "
                            + "VALUES ('t', 'a@b.c', 'hash', now(), 'NOT_A_PURPOSE')"));
            assertThrows(SQLException.class, () -> execute(connection,
                    "INSERT INTO pending_signup_entity (token, user_mail, user_password, expires_at) "
                            + "VALUES ('t', 'a@b.c', 'hash', now())"));
        }
    }

    @Test
    void createsTheSupportOutboxOnAFreshDatabase() throws SQLException {
        migrate();

        try (Connection connection = openConnection()) {
            assertEquals(0, countRows(connection, "support_outbox_entity"));
            assertColumn(connection, "support_outbox_entity", "event_id", "character varying", 64L, null);
            assertColumn(connection, "support_outbox_entity", "payload", "text", null, null);
            assertColumn(connection, "support_outbox_entity", "status", "character varying", 16L, null);
            assertColumn(connection, "support_outbox_entity", "next_attempt_at", "timestamp without time zone", null, null);
            assertTrue(isNotNullColumn(connection, "support_outbox_entity", "next_attempt_at"),
                    "a terminal event must never need a null retry timestamp");
            assertConstraint(connection, "support_outbox_entity", "chk_support_outbox_status");
            assertTrue(indexExists(connection, "idx_support_outbox_due"));
            assertTrue(indexExists(connection, "idx_support_outbox_lease"));
            assertTrue(indexExists(connection, "idx_support_outbox_customer"));

            assertThrows(SQLException.class, () -> execute(connection,
                    "INSERT INTO support_outbox_entity (event_id, event_type, user_mail, payload, status, "
                            + "attempt_count, next_attempt_at, created_at) "
                            + "VALUES ('e', 'CUSTOMER_MESSAGE_CREATED', 'a@b.c', '{}', 'NOT_A_STATUS', 0, now(), now())"));
        }
    }

    @Test
    void createsTheSupportOutboxAlongsideAnExistingSchema() throws SQLException {
        createLegacySchemaWithPendingTokens();

        migrate();

        try (Connection connection = openConnection()) {
            assertEquals(0, countRows(connection, "support_outbox_entity"));
            assertConstraint(connection, "support_outbox_entity", "chk_support_outbox_status");
            assertColumn(connection, "pending_signup_entity", "token_purpose", "character varying", 32L, null);
        }
    }

    @Test
    void theOutboxRejectsADuplicateEventId() throws SQLException {
        migrate();

        try (Connection connection = openConnection()) {
            execute(connection, insertOutbox("same-id"));
            // The event id is also the payload's dedup key, so a second insert
            // for the same id must fail rather than fork the event.
            assertThrows(SQLException.class, () -> execute(connection, insertOutbox("same-id")));
        }
    }

    @Test
    void sequenceNumbersAreAssignedPerEventAndPreserveInsertionOrder() throws SQLException {
        migrate();

        try (Connection connection = openConnection()) {
            execute(connection, insertOutbox("first"));
            execute(connection, insertOutbox("second"));

            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT event_id FROM support_outbox_entity ORDER BY sequence_no")) {
                assertTrue(rs.next());
                assertEquals("first", rs.getString(1));
                assertTrue(rs.next());
                assertEquals("second", rs.getString(1));
            }
        }
    }

    private String insertOutbox(String eventId) {
        return "INSERT INTO support_outbox_entity (event_id, event_type, user_mail, payload, status, "
                + "attempt_count, next_attempt_at, created_at) VALUES ('" + eventId
                + "', 'CUSTOMER_MESSAGE_CREATED', 'a@b.c', '{}', 'PENDING', 0, now(), now())";
    }

    private void createLegacySchemaWithPendingTokens() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE pending_signup_entity ("
                    + "token VARCHAR(64) PRIMARY KEY, "
                    + "expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL, "
                    + "user_mail VARCHAR(255) NOT NULL, "
                    + "user_password VARCHAR(255) NOT NULL)");
            statement.execute("INSERT INTO pending_signup_entity VALUES "
                    + "('legacy-signup', now() + interval '10 minutes', 'a@b.c', 'hash'), "
                    + "('legacy-expired', now() - interval '10 minutes', 'd@e.f', 'hash')");
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

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int countRows(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private void assertColumn(Connection connection, String table, String column, String dataType,
                              Long characterMaxLength, Integer numericPrecision) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT data_type, character_maximum_length, numeric_precision FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                             + "AND column_name = '" + column + "'")) {
            assertTrue(rs.next(), "missing column " + table + "." + column);
            assertEquals(dataType, rs.getString("data_type"));
            if (characterMaxLength != null) {
                assertEquals(characterMaxLength.longValue(), rs.getLong("character_maximum_length"));
            }
            if (numericPrecision != null) {
                assertEquals(numericPrecision.longValue(), rs.getLong("numeric_precision"));
            }
        }
    }

    private boolean isNotNullColumn(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT is_nullable FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                             + "AND column_name = '" + column + "'")) {
            assertTrue(rs.next(), "missing column " + table + "." + column);
            return "NO".equals(rs.getString("is_nullable"));
        }
    }

    private boolean hasColumnDefault(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT column_default FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                             + "AND column_name = '" + column + "'")) {
            assertTrue(rs.next(), "missing column " + table + "." + column);
            return rs.getString(1) != null;
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

    private boolean indexExists(Connection connection, String indexName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = '"
                             + indexName + "'")) {
            assertTrue(rs.next());
            return rs.getInt(1) == 1;
        }
    }
}
