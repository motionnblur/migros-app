package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.user.category.SubCategoryDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.exception.user.CategoryNotFoundException;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductDescriptionEntityRepository;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.product.ProductImageEntityRepository;
import com.example.MigrosBackend.repository.product.SubcategoryCount;
import com.example.MigrosBackend.service.global.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The subcategory counts used to be computed by loading a category's whole
 * product list and grouping it in memory. They are now a single GROUP BY query.
 * This test seeds products, derives the counts with the previous in-memory
 * algorithm, feeds that same result back as the repository projection, and
 * checks the service returns an equivalent payload.
 */
@ExtendWith(MockitoExtension.class)
class UserCatalogReadServiceTest {

    @Mock
    private CategoryEntityRepository categoryEntityRepository;
    @Mock
    private ProductEntityRepository productEntityRepository;
    @Mock
    private ProductImageEntityRepository productImageEntityRepository;
    @Mock
    private ProductDescriptionEntityRepository productDescriptionEntityRepository;
    @Mock
    private FileService fileService;

    private UserCatalogReadService service;

    @BeforeEach
    void setUp() {
        service = new UserCatalogReadService(
                categoryEntityRepository,
                productEntityRepository,
                productImageEntityRepository,
                productDescriptionEntityRepository,
                fileService);
    }

    @Test
    void groupByCountsMatchThePreviousInMemoryGrouping() {
        Long categoryId = 7L;
        List<ProductEntity> seeded = List.of(
                product("Fruits", 3),
                product("Fruits", 1),
                product("Fruits", 0),
                product("Dairy", 2),
                product("", 4),
                product(null, 5));

        Map<String, Long> inMemory = seeded.stream()
                .filter(product -> product.getProductCount() > 0)
                .filter(product -> product.getSubcategoryName() != null && !product.getSubcategoryName().isEmpty())
                .collect(Collectors.groupingBy(ProductEntity::getSubcategoryName, Collectors.counting()));

        List<SubcategoryCount> groupByResult = inMemory.entrySet().stream()
                .map(entry -> new SubcategoryCount(entry.getKey(), entry.getValue()))
                .toList();

        CategoryEntity category = new CategoryEntity();
        category.setId(categoryId);
        when(categoryEntityRepository.findById(categoryId)).thenReturn(Optional.of(category));
        when(productEntityRepository.countProductsBySubcategory(categoryId)).thenReturn(groupByResult);

        List<SubCategoryDto> result = service.getSubCategories(categoryId);

        Map<String, Integer> resultCounts = result.stream()
                .collect(Collectors.toMap(SubCategoryDto::getSubCategoryName, SubCategoryDto::getProductCount));
        assertEquals(Map.of("Fruits", 2, "Dairy", 1), resultCounts);
        result.forEach(dto -> assertEquals(categoryId, dto.getSubCategoryId()));
    }

    @Test
    void emptyPageReturnsAnEmptyListInsteadOfAnError() {
        Long categoryId = 3L;
        when(categoryEntityRepository.existsById(categoryId)).thenReturn(true);
        when(productEntityRepository.findByCategoryEntityIdAndProductCountGreaterThan(
                eq(categoryId), eq(0), any(Pageable.class))).thenReturn(Page.empty());

        List<ProductPreviewDto> result = service.getProductsFromCategory(categoryId, 5, 10);

        assertTrue(result.isEmpty(), "a page past the end must be an empty listing, not an error");
    }

    @Test
    void nonexistentCategoryStillThrowsCategoryNotFoundException() {
        Long categoryId = 404L;
        when(categoryEntityRepository.existsById(categoryId)).thenReturn(false);

        assertThrows(CategoryNotFoundException.class,
                () -> service.getProductsFromCategory(categoryId, 0, 10));
    }

    private ProductEntity product(String subcategoryName, int count) {
        ProductEntity product = new ProductEntity();
        product.setProductName("P-" + subcategoryName + "-" + count);
        product.setSubcategoryName(subcategoryName);
        product.setProductCount(count);
        product.setProductPrice(new BigDecimal("10.00"));
        product.setProductDiscount(BigDecimal.ZERO);
        product.setProductDescription("seeded");
        return product;
    }
}
