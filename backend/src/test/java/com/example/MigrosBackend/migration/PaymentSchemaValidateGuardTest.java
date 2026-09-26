package com.example.MigrosBackend.migration;

import com.example.MigrosBackend.MigrosBackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves the end-state wiring: with {@code ddl-auto=validate} the application
 * starts on Flyway-owned schemas (fresh and legacy-upgraded) and fails fast
 * when a required payment structure was never created because the
 * reconciliation migration did not run.
 */
@Testcontainers
class PaymentSchemaValidateGuardTest {

    private static final String USER_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String ADMIN_SECRET = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Test
    void startupFailsWhenReconciliationDidNotRun() throws SQLException {
        resetSchema();
        // Flyway stops at V4: V2/V3/V4 skip their bodies on the empty database,
        // so no payment table exists and Hibernate validation must fail fast.
        Throwable failure = assertBootFails(Map.of("spring.flyway.target", "4"));
        assertChainContains(failure, "missing table");
    }

    @Test
    void legacyUpgradedDatabaseStartsWithValidation() throws SQLException {
        resetSchema();
        createLegacyPreV1Base();
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO user_entity (user_entity_id, user_mail) VALUES "
                    + "(1, 'legacy-boot@migros.com')");
            statement.execute("INSERT INTO product_entity (product_entity_id, product_name, subcategory_name, "
                    + "product_count, product_price, product_discount, product_description) VALUES "
                    + "(1, 'plain', 'general', 10, 50.25, 10.0, 'legacy product')");
        }

        // Full Flyway run (V1..V5) happens inside application startup; success
        // proves a legacy upgrade boots with Hibernate validation enabled.
        try (ConfigurableApplicationContext context = boot(Map.of())) {
            assertTrue(context.isRunning(), "application context must be running after legacy upgrade");
        }
    }

    private Throwable assertBootFails(Map<String, String> extra) {
        try (ConfigurableApplicationContext context = boot(extra)) {
            context.close();
        } catch (Throwable failure) {
            return failure;
        }
        fail("application startup must fail when the reconciliation migration did not run");
        return null;
    }

    private ConfigurableApplicationContext boot(Map<String, String> extra) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        properties.put("spring.datasource.username", POSTGRES.getUsername());
        properties.put("spring.datasource.password", POSTGRES.getPassword());
        properties.put("jwt.user-secret", USER_SECRET);
        properties.put("jwt.admin-secret", ADMIN_SECRET);
        properties.put("support.internal.key", "integration-test-internal-key");
        properties.putAll(extra);
        org.springframework.core.env.MapPropertySource testGuard =
                new org.springframework.core.env.MapPropertySource("paymentSchemaGuard", properties);
        return new SpringApplicationBuilder(MigrosBackendApplication.class)
                .profiles("local")
                .web(WebApplicationType.NONE)
                .initializers(context ->
                        context.getEnvironment().getPropertySources().addFirst(testGuard))
                .logStartupInfo(false)
                .run();
    }

    private void assertChainContains(Throwable failure, String fragment) {
        StringBuilder chain = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            chain.append(current).append("\n");
        }
        assertTrue(chain.toString().toLowerCase().contains(fragment.toLowerCase()),
                "startup failure must mention '" + fragment + "', got:\n" + chain);
    }

    private void resetSchema() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
    }

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

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
