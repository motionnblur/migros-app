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
 * Proves V9 repairs a genuinely populated database: duplicate mailboxes are
 * merged (children re-pointed to the survivor) before the unique constraints
 * are added, so the migration cannot fail on a pre-existing duplicate.
 */
@Testcontainers
class UserMailUniquenessMigrationPostgresTest {

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
    void duplicateMailboxesAndTokensAreMergedByV9() throws SQLException {
        migrateTo("8");

        long survivorId;
        long duplicateId;
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO user_entity (user_mail, user_password) "
                    + "VALUES ('dup@migros.com', 'hash-1')");
            statement.execute("INSERT INTO user_entity (user_mail, user_password) "
                    + "VALUES ('dup@migros.com', 'hash-2')");
            statement.execute("INSERT INTO order_entity (user_entity_id) VALUES (2)");
            statement.execute("INSERT INTO order_group_entity (user_entity_id) VALUES (2)");
            statement.execute("INSERT INTO pending_signup_entity "
                    + "(token, expires_at, user_mail, user_password, token_purpose) VALUES "
                    + "('token-a', now() + interval '10 minutes', 'dup@migros.com', 'hash', 'SIGNUP'), "
                    + "('token-b', now() + interval '10 minutes', 'dup@migros.com', 'hash', 'SIGNUP'), "
                    + "('token-c', now() + interval '10 minutes', 'dup@migros.com', 'hash', 'PASSWORD_RESET')");
        }

        survivorId = singleLong("SELECT MIN(user_entity_id) FROM user_entity WHERE user_mail = 'dup@migros.com'");
        duplicateId = singleLong("SELECT MAX(user_entity_id) FROM user_entity WHERE user_mail = 'dup@migros.com'");
        assertTrue(survivorId < duplicateId, "test setup must create two distinct user rows");

        MigrateResult result = migrate();
        assertEquals(MigrationTestSupport.versionedMigrationCount() - 8, result.migrationsExecuted,
                "everything after V8 must still be pending here");

        assertEquals(1, singleLong("SELECT COUNT(*) FROM user_entity WHERE user_mail = 'dup@migros.com'"),
                "duplicate mailboxes must be merged to one row");
        assertEquals(survivorId, singleLong("SELECT user_entity_id FROM user_entity WHERE user_mail = 'dup@migros.com'"),
                "the surviving row is the lowest id");
        assertEquals(survivorId, singleLong("SELECT user_entity_id FROM order_entity"),
                "order lines must be re-pointed to the survivor");
        assertEquals(survivorId, singleLong("SELECT user_entity_id FROM order_group_entity"),
                "order groups must be re-pointed to the survivor");

        assertEquals(1, singleLong("SELECT COUNT(*) FROM pending_signup_entity "
                + "WHERE user_mail = 'dup@migros.com' AND token_purpose = 'SIGNUP'"),
                "one pending token per (mailbox, purpose)");
        assertEquals(1, singleLong("SELECT COUNT(*) FROM pending_signup_entity "
                + "WHERE user_mail = 'dup@migros.com' AND token_purpose = 'PASSWORD_RESET'"),
                "a different purpose keeps its own token");

        assertThrows(SQLException.class, () -> execute("INSERT INTO user_entity (user_mail) VALUES ('dup@migros.com')"),
                "the unique constraint must reject a new duplicate");
        assertThrows(SQLException.class, () -> execute("INSERT INTO pending_signup_entity "
                + "(token, expires_at, user_mail, user_password, token_purpose) VALUES "
                + "('token-d', now() + interval '10 minutes', 'dup@migros.com', 'hash', 'SIGNUP')"),
                "the pending unique constraint must reject a second live token");
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
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
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
