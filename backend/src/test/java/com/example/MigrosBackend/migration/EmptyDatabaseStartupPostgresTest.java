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
}
