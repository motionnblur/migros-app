package com.example.MigrosBackend.repository.product;

import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.helper.ProductPricingPolicy;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the {@code GROUP BY} subcategory-count query returns exactly the same
 * numbers the previous in-memory grouping produced for the same seeded rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class SubCategoryCountsPostgresTest {

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
    private ProductEntityRepository productEntityRepository;
    @Autowired
    private CategoryEntityRepository categoryEntityRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE product_entity, category_entity RESTART IDENTITY CASCADE");
    }

    @Test
    void groupByQueryMatchesThePreviousInMemoryGrouping() {
        CategoryEntity category = new CategoryEntity();
        category.setCategoryId(1);
        category.setCategoryName("Grocery");
        category = categoryEntityRepository.saveAndFlush(category);
        Long categoryId = category.getId();

        seed(category, "Fruits", 3);
        seed(category, "Fruits", 1);
        seed(category, "Fruits", 0);
        seed(category, "Dairy", 2);
        seed(category, "", 4);

        // The previous implementation grouped the category's products in memory.
        List<ProductEntity> categoryProducts = productEntityRepository.findAll().stream()
                .filter(product -> product.getCategoryEntity() != null
                        && categoryId.equals(product.getCategoryEntity().getId()))
                .toList();
        Map<String, Long> inMemory = categoryProducts.stream()
                .filter(product -> product.getProductCount() > 0)
                .filter(product -> product.getSubcategoryName() != null
                        && !product.getSubcategoryName().isEmpty())
                .collect(Collectors.groupingBy(ProductEntity::getSubcategoryName, Collectors.counting()));

        Map<String, Long> groupBy = productEntityRepository.countProductsBySubcategory(categoryId).stream()
                .collect(Collectors.toMap(SubcategoryCount::subcategoryName, SubcategoryCount::productCount));

        assertEquals(inMemory, groupBy);
    }

    private void seed(CategoryEntity category, String subcategoryName, int productCount) {
        ProductEntity product = new ProductEntity();
        product.setProductName("P-" + subcategoryName + "-" + productCount);
        product.setSubcategoryName(subcategoryName);
        product.setProductCount(productCount);
        product.setProductPrice(new BigDecimal("10.00"));
        product.setProductDiscount(BigDecimal.ZERO);
        product.setEffectivePrice(
                ProductPricingPolicy.effectivePrice(product.getProductPrice(), product.getProductDiscount()));
        product.setProductDescription("seeded");
        product.setCategoryEntity(category);
        productEntityRepository.saveAndFlush(product);
    }
}
