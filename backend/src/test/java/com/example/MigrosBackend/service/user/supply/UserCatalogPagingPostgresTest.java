package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.user.category.SubCategoryDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.exception.user.CategoryNotFoundException;
import com.example.MigrosBackend.helper.ProductPricingPolicy;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Product paging against a real database rather than a mock.
 *
 * <p>These cases cover the property a mocked repository cannot show: with more
 * than two pages of rows and nothing changing between reads, the database must
 * hand back the same rows in the same order every time, and two adjacent
 * windows must not share a row. A page request with no {@code ORDER BY} has no
 * guaranteed order at all, so PostgreSQL may return the same rows in a
 * different order for each of the two windowed queries, which duplicates and
 * drops rows in the listing the client renders.
 *
 * <p>The counts are asserted against the same seeded rows, because the client
 * sizes its paginator from them. A count that disagrees with the listing
 * predicate produces a page the client believes exists and the server does not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class UserCatalogPagingPostgresTest {

    private static final String USER_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String ADMIN_SECRET = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";

    private static final int PAGE_SIZE = 4;

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
    private UserCatalogReadService catalogReadService;

    @Autowired
    private CategoryEntityRepository categoryEntityRepository;

    @Autowired
    private ProductEntityRepository productEntityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long categoryA;
    private List<Long> categoryAIds;
    private List<Long> fruitsIds;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE TABLE product_entity, category_entity RESTART IDENTITY CASCADE");

        categoryA = saveCategory("Grocery", 1);
        Long categoryB = saveCategory("Electronics", 2);

        // Twelve in-stock rows in category A: exactly three pages of four.
        List<Long> all = new ArrayList<>();
        List<Long> fruits = seedProducts(categoryA, "Fruits", 7);
        List<Long> dairy = seedProducts(categoryA, "Dairy", 5);
        all.addAll(fruits);
        all.addAll(dairy);
        // Three more in category B, so the "all products" listing spans four
        // pages of four.
        all.addAll(seedProducts(categoryB, "Phones", 3));
        // Out of stock, so they must appear in no listing and in no count.
        seedProducts(categoryA, "Fruits", 0);
        seedProducts(categoryB, "Phones", 0);

        categoryAIds = all.stream().limit(12).toList();
        fruitsIds = List.copyOf(fruits);
    }

    @Test
    void moreThanTwoPagesOfUnchangedDataAreRepeatableAndNeverOverlap() {
        List<Long> first = ids(catalogReadService.getProductsFromCategory(categoryA, 0, PAGE_SIZE));
        List<Long> second = ids(catalogReadService.getProductsFromCategory(categoryA, 1, PAGE_SIZE));
        List<Long> third = ids(catalogReadService.getProductsFromCategory(categoryA, 2, PAGE_SIZE));

        assertEquals(4, first.size());
        assertEquals(4, second.size());
        assertEquals(4, third.size());
        assertEquals(categoryAIds, concat(first, second, third));
        assertNoOverlap(List.of(first, second, third));

        // Re-reading unchanged data must return exactly the same windows.
        assertEquals(first, ids(catalogReadService.getProductsFromCategory(categoryA, 0, PAGE_SIZE)));
        assertEquals(second, ids(catalogReadService.getProductsFromCategory(categoryA, 1, PAGE_SIZE)));
        assertEquals(third, ids(catalogReadService.getProductsFromCategory(categoryA, 2, PAGE_SIZE)));
    }

    @Test
    void theWholeListingIsTheAscendingConcatenationOfItsPages() {
        List<Long> whole = ids(catalogReadService.getAllProducts(0, 100));
        assertEquals(15, whole.size());
        assertEquals(15, catalogReadService.getAllProductCounts());
        assertEquals(whole.stream().sorted().toList(), whole,
                "the listing must be primary key ascending, otherwise a page boundary is arbitrary");

        List<Long> paged = concat(
                ids(catalogReadService.getAllProducts(0, 5)),
                ids(catalogReadService.getAllProducts(1, 5)),
                ids(catalogReadService.getAllProducts(2, 5)));

        assertEquals(whole, paged);
        assertNoOverlap(List.of(
                ids(catalogReadService.getAllProducts(0, 5)),
                ids(catalogReadService.getAllProducts(1, 5)),
                ids(catalogReadService.getAllProducts(2, 5))));
    }

    @Test
    void subcategoryPagesAreOrderedDisjointAndAgreeWithTheSubcategoryCount() {
        List<Long> first = ids(catalogReadService.getProductsFromSubcategory("Fruits", 0, PAGE_SIZE));
        List<Long> second = ids(catalogReadService.getProductsFromSubcategory("Fruits", 1, PAGE_SIZE));

        assertEquals(4, first.size());
        assertEquals(3, second.size());
        assertEquals(fruitsIds, concat(first, second));
        assertNoOverlap(List.of(first, second));
        assertEquals(fruitsIds.size(), catalogReadService.getProductCountsFromSubcategory("Fruits"));
    }

    @Test
    void theCategoryCountMatchesTheRowsTheListingPaginatesOver() {
        int declaredCount = catalogReadService.getProductCountsFromCategory(categoryA);

        List<Long> paged = new ArrayList<>();
        for (int page = 0; page < 20; page++) {
            List<Long> window = ids(catalogReadService.getProductsFromCategory(categoryA, page, PAGE_SIZE));
            if (window.isEmpty()) {
                break;
            }
            paged.addAll(window);
        }

        assertEquals(12, declaredCount);
        assertEquals(12, paged.size());
        assertEquals(categoryAIds, paged);
        assertEquals(paged.stream().sorted().toList(), paged);
        assertEquals(5, catalogReadService.getSubCategories(categoryA).stream()
                .filter(dto -> "Dairy".equals(dto.getSubCategoryName()))
                .mapToInt(SubCategoryDto::getProductCount)
                .sum());
    }

    @Test
    void aPageBeyondTheLastOneIsAnEmptySuccessNotAnError() {
        assertTrue(catalogReadService.getProductsFromCategory(categoryA, 99, PAGE_SIZE).isEmpty());
        assertTrue(catalogReadService.getAllProducts(99, PAGE_SIZE).isEmpty());
        assertTrue(catalogReadService.getProductsFromSubcategory("Fruits", 99, PAGE_SIZE).isEmpty());

        // A category with no in-stock rows at all is also an empty page, and its
        // count agrees with that emptiness.
        Long empty = saveCategory("Empty", 3);
        assertEquals(0, catalogReadService.getProductCountsFromCategory(empty));
        assertTrue(catalogReadService.getProductsFromCategory(empty, 0, PAGE_SIZE).isEmpty());
    }

    @Test
    void aNonexistentCategoryIsStillNotFoundAndTheBoundRejectsBadPagesBeforeQuerying() {
        assertThrows(CategoryNotFoundException.class,
                () -> catalogReadService.getProductsFromCategory(categoryA + 9999, 0, PAGE_SIZE));

        assertThrows(GeneralException.class, () -> catalogReadService.getProductsFromCategory(categoryA, -1, PAGE_SIZE));
        assertThrows(GeneralException.class, () -> catalogReadService.getProductsFromCategory(categoryA, 0, 0));
        assertThrows(GeneralException.class, () -> catalogReadService.getProductsFromCategory(categoryA, 0, 101));
        assertThrows(GeneralException.class, () -> catalogReadService.getAllProducts(0, 101));
        assertThrows(GeneralException.class, () -> catalogReadService.getProductsFromSubcategory("Fruits", 0, 0));

        // A rejected request must not have changed anything.
        assertEquals(12, catalogReadService.getProductCountsFromCategory(categoryA));
        assertEquals(15, catalogReadService.getAllProductCounts());
    }

    private Long saveCategory(String name, int legacyCategoryId) {
        CategoryEntity category = new CategoryEntity();
        category.setCategoryName(name);
        category.setCategoryId(legacyCategoryId);
        return categoryEntityRepository.saveAndFlush(category).getId();
    }

    /**
     * Inserts {@code inStock} rows with stock, and the same number again with no
     * stock. Returns the ids of the in-stock rows only, in insertion order, which
     * is primary key ascending order.
     */
    private List<Long> seedProducts(Long categoryId, String subcategoryName, int inStock) {
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < inStock * 2; index++) {
            ProductEntity product = new ProductEntity();
            product.setProductName(subcategoryName + "-" + index);
            product.setSubcategoryName(subcategoryName);
            product.setProductCount(index < inStock ? 5 : 0);
            product.setProductPrice(new BigDecimal("10.00"));
            product.setProductDiscount(BigDecimal.ZERO);
            product.setEffectivePrice(
                    ProductPricingPolicy.effectivePrice(product.getProductPrice(), product.getProductDiscount()));
            product.setProductDescription("seeded");
            product.setCategoryEntity(categoryEntityRepository.getReferenceById(categoryId));
            Long id = productEntityRepository.saveAndFlush(product).getId();
            if (index < inStock) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static List<Long> ids(List<ProductPreviewDto> products) {
        return products.stream().map(ProductPreviewDto::getProductId).toList();
    }

    @SafeVarargs
    private static List<Long> concat(List<Long>... pages) {
        return Stream.of(pages).flatMap(List::stream).toList();
    }

    private static void assertNoOverlap(List<List<Long>> pages) {
        Set<Long> seen = new LinkedHashSet<>();
        pages.forEach(page -> page.forEach(id ->
                assertTrue(seen.add(id), "row " + id + " appears in more than one page")));
    }
}
