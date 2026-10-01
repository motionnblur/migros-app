package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionTabDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The description tabs of one product are one unit of work.
 *
 * <p>Persisting them one row at a time leaves a partially written description
 * set behind when a later row is rejected: the request fails but the product
 * keeps the tabs that happened to be written first. Building the full list and
 * saving it inside one transaction makes the whole request all-or-nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class AdminProductDescriptionTransactionPostgresTest {

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
        registry.add("support.service.internal-key", () -> "integration-test-internal-key");
    }

    @Autowired
    private AdminSupplyService adminSupplyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE TABLE product_description_entity, product_entity RESTART IDENTITY CASCADE");
        jdbcTemplate.update("INSERT INTO product_entity "
                + "(product_entity_id, product_name, subcategory_name, product_count, product_price, "
                + "product_discount, effective_price, product_description) VALUES "
                + "(1, 'Product', 'Sub', 1, 1.00, 0.00, 1.00, 'desc')");
    }

    @Test
    void aValidBatchIsPersistedTogether() {
        ProductDescriptionListDto dto = new ProductDescriptionListDto();
        dto.setProductId(1L);
        dto.setDescriptionList(List.of(
                new ProductDescriptionTabDto(null, "First", "a"),
                new ProductDescriptionTabDto(null, "Second", "b")));

        adminSupplyService.addProductDescription(dto);

        assertEquals(2, descriptionCount());
    }

    @Test
    void aMidBatchFailureRollsBackTheWholeBatch() {
        ProductDescriptionListDto dto = new ProductDescriptionListDto();
        dto.setProductId(1L);
        dto.setDescriptionList(List.of(
                new ProductDescriptionTabDto(null, "First", "a"),
                new ProductDescriptionTabDto(null, "Overflow", "x".repeat(300))));

        assertThrows(RuntimeException.class, () -> adminSupplyService.addProductDescription(dto));

        assertEquals(0, descriptionCount(),
                "a rejected tab must not leave the tabs written before it behind");
    }

    private int descriptionCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_description_entity", Integer.class);
    }
}
