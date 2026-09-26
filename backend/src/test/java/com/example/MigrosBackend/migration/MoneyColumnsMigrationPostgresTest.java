package com.example.MigrosBackend.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class MoneyColumnsMigrationPostgresTest {

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
    void legacyRealColumnsAreConvertedToNumericAndValuesRetained() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE product_entity ("
                    + "product_entity_id BIGSERIAL PRIMARY KEY, "
                    + "product_name VARCHAR(255) NOT NULL, "
                    + "product_price REAL NOT NULL, "
                    + "product_discount REAL NOT NULL)");
            statement.execute("CREATE TABLE order_entity ("
                    + "order_entity_id BIGSERIAL PRIMARY KEY, "
                    + "price REAL, "
                    + "total_price REAL)");
            statement.execute("INSERT INTO product_entity (product_name, product_price, product_discount) VALUES "
                    + "('plain', 50.25, 10.0), "
                    + "('round-up', 10.125, 0.0), "
                    + "('round-down', 10.375, 12.5)");
            statement.execute("INSERT INTO order_entity (price, total_price) VALUES (25.25, 75.75)");
        }

        migrate();

        assertNumeric19Scale2("product_entity", "product_price");
        assertNumeric19Scale2("product_entity", "product_discount");
        assertNumeric19Scale2("order_entity", "price");
        assertNumeric19Scale2("order_entity", "total_price");

        assertDecimal("SELECT product_price FROM product_entity WHERE product_name = 'plain'", "50.25");
        assertDecimal("SELECT product_price FROM product_entity WHERE product_name = 'round-up'", "10.13");
        assertDecimal("SELECT product_price FROM product_entity WHERE product_name = 'round-down'", "10.38");
        assertDecimal("SELECT product_discount FROM product_entity WHERE product_name = 'round-down'", "12.50");
        assertDecimal("SELECT total_price FROM order_entity", "75.75");
    }

    @Test
    void flywayRecordsMigrationInHistory() throws SQLException {
        migrate();

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT success, type FROM flyway_schema_history WHERE version = '1'")) {
            assertTrue(rs.next(), "expected a migration row for version 1");
            assertTrue(rs.getBoolean("success"), "migration version 1 must be recorded as successful");
            assertEquals("SQL", rs.getString("type"));
        }
    }

    @Test
    void secondMigrateIsSafeAndDoesNotReapply() throws SQLException {
        MigrateResult first = migrate();
        assertEquals(4, first.migrationsExecuted, "V1, V2, V3 and V4 must run on a first migrate");

        MigrateResult second = migrate();

        assertEquals(0, second.migrationsExecuted, "the migration must not be reapplied");
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
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

    private void assertNumeric19Scale2(String tableName, String columnName) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT data_type, numeric_precision, numeric_scale FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = '" + tableName + "' "
                             + "AND column_name = '" + columnName + "'")) {
            assertTrue(rs.next(), "column " + tableName + "." + columnName + " must exist");
            assertEquals("numeric", rs.getString("data_type"));
            assertEquals(19, rs.getInt("numeric_precision"));
            assertEquals(2, rs.getInt("numeric_scale"));
        }
    }

    private void assertDecimal(String sql, String expected) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "expected a row for: " + sql);
            BigDecimal actual = rs.getBigDecimal(1);
            assertEquals(0, new BigDecimal(expected).compareTo(actual),
                    "expected " + expected + " but was " + actual);
        }
    }
}
