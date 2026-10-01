package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.user.category.SubCategoryDto;
import com.example.MigrosBackend.dto.user.product.ProductDetailDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.exception.shared.GeneralException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The subcategory counts used to be computed by loading a category's whole
 * product list and grouping it in memory. They are now a single GROUP BY query.
 * This test seeds products, derives the counts with the previous in-memory
 * algorithm, feeds that same result back as the repository projection, and
 * checks the service returns an equivalent payload.
 *
 * <p>The paging cases here use mocks, so they can only prove what page request
 * the service builds. {@code UserCatalogPagingPostgresTest} proves what the
 * database does with it.
 */
@ExtendWith(MockitoExtension.class)
class UserCatalogReadServiceTest {

    /**
     * Every product shape a valid stored row can take, as
     * {@code {price, discountPercent}}; a null discount is a row written before
     * the column became {@code NOT NULL} and is still displayed.
     */
    private static final List<String[]> VALID_PRODUCTS = List.of(
            new String[]{"10.00", "0"},
            new String[]{"10.00", "0.00"},
            new String[]{"10.00", null},
            new String[]{"10.00", "12.5"},
            new String[]{"10.01", "12.5"},
            new String[]{"10", "0"},
            new String[]{"10.5", "0.00"},
            new String[]{"0.00", "0"},
            new String[]{"0.00", "100"},
            new String[]{"0.01", "0"},
            new String[]{"0.01", "50"},
            new String[]{"1.00", "0.49"},
            new String[]{"1.00", "0.50"},
            new String[]{"1.00", "0.51"},
            new String[]{"1.00", "100"},
            new String[]{"2.00", "99.99"},
            new String[]{"7.35", "15"},
            new String[]{"19.99", "0.01"},
            new String[]{"12345.67", "1"},
            new String[]{"10.00", "100"},
            new String[]{"10.00", "100.00"},
            new String[]{"1000000000.05", "0"},
            new String[]{"1000000000.05", "50"},
            new String[]{"99999999999999999.99", "0"},
            new String[]{"99999999999999999.99", "10"},
            new String[]{"99999999999999999.99", "100"});

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

    @Test
    void anOutOfRangePageIsRejectedBeforeAnyQueryRuns() {
        Long categoryId = 3L;

        assertThrows(GeneralException.class, () -> service.getProductsFromCategory(categoryId, -1, 10));
        assertThrows(GeneralException.class, () -> service.getProductsFromCategory(categoryId, 0, 0));
        assertThrows(GeneralException.class, () -> service.getProductsFromCategory(categoryId, 0, -1));
        assertThrows(GeneralException.class, () -> service.getProductsFromCategory(categoryId, 0, 101));
        assertThrows(GeneralException.class, () -> service.getProductsFromCategory(categoryId, 0, Integer.MAX_VALUE));

        verifyNoInteractions(categoryEntityRepository);
        verifyNoInteractions(productEntityRepository);
    }

    @Test
    void anOutOfRangePageIsRejectedBeforeAnyQueryRunsForTheOtherListings() {
        assertThrows(GeneralException.class, () -> service.getAllProducts(-1, 10));
        assertThrows(GeneralException.class, () -> service.getAllProducts(0, 0));
        assertThrows(GeneralException.class, () -> service.getAllProducts(0, 101));

        assertThrows(GeneralException.class, () -> service.getProductsFromSubcategory("Fruits", -1, 10));
        assertThrows(GeneralException.class, () -> service.getProductsFromSubcategory("Fruits", 0, 0));
        assertThrows(GeneralException.class, () -> service.getProductsFromSubcategory("Fruits", 0, 101));

        verifyNoInteractions(productEntityRepository);
    }

    @Test
    void aPageAtEitherSizeBoundIsAccepted() {
        when(categoryEntityRepository.existsById(3L)).thenReturn(true);
        when(productEntityRepository.findByCategoryEntityIdAndProductCountGreaterThan(eq(3L), eq(0), any(Pageable.class)))
                .thenReturn(Page.empty());

        assertTrue(service.getProductsFromCategory(3L, 0, 1).isEmpty());
        assertTrue(service.getProductsFromCategory(3L, 0, 100).isEmpty());
    }

    @Test
    void everyProductListingIsOrderedByProductIdAscending() {
        when(categoryEntityRepository.existsById(3L)).thenReturn(true);
        when(productEntityRepository.findByCategoryEntityIdAndProductCountGreaterThan(eq(3L), eq(0), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(productEntityRepository.findByProductCountGreaterThan(eq(0), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(productEntityRepository.findBySubcategoryNameAndProductCountGreaterThan(eq("Fruits"), eq(0), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.getProductsFromCategory(3L, 1, 10);
        service.getAllProducts(2, 10);
        service.getProductsFromSubcategory("Fruits", 3, 10);

        ArgumentCaptor<Pageable> pageables = ArgumentCaptor.forClass(Pageable.class);
        verify(productEntityRepository).findByCategoryEntityIdAndProductCountGreaterThan(eq(3L), eq(0), pageables.capture());
        verify(productEntityRepository).findByProductCountGreaterThan(eq(0), pageables.capture());
        verify(productEntityRepository).findBySubcategoryNameAndProductCountGreaterThan(eq("Fruits"), eq(0), pageables.capture());

        assertEquals(3, pageables.getAllValues().size());
        pageables.getAllValues().forEach(pageable -> {
            assertEquals(Sort.by(Sort.Direction.ASC, "id"), pageable.getSort(),
                    "an unsorted page request has no ORDER BY, so the database may repeat or skip rows across windows");
        });
        assertEquals(1, pageables.getAllValues().get(0).getPageNumber());
        assertEquals(2, pageables.getAllValues().get(1).getPageNumber());
        assertEquals(3, pageables.getAllValues().get(2).getPageNumber());
        pageables.getAllValues().forEach(pageable -> assertEquals(10, pageable.getPageSize()));
    }

    /**
     * Pins the extraction of the discount arithmetic into
     * {@code ProductPricingPolicy}: the price a listing shows and a cart line is
     * built from is byte-identical to what the pre-extraction method returned,
     * for every shape a valid stored row can take.
     *
     * <p>The frozen copy is deliberate. Comparing the new code against a second
     * copy of the new code would only prove it equals itself.
     */
    @Test
    void getEffectivePriceMatchesThePreviousInlineImplementationForEveryValidProduct() {
        for (String[] valid : VALID_PRODUCTS) {
            ProductEntity product = pricedProduct(valid[0], valid[1]);
            assertEquals(inlineListingPriceBeforeExtraction(product),
                    service.getEffectivePrice(product),
                    "price " + valid[0] + " at " + valid[1] + "% must be unchanged by the extraction");
        }
    }

    /**
     * The number a customer reads in the cart is the number checkout charges.
     * Both are now one policy, and the checkout-side reference is the frozen
     * pre-extraction checkout code rather than the current
     * {@code CheckoutCalculations}, which is package-private in another package
     * and therefore not reachable from here.
     */
    @Test
    void everyValidProductDisplaysTheAmountCheckoutChargedForIt() {
        for (String[] valid : VALID_PRODUCTS) {
            ProductEntity product = pricedProduct(valid[0], valid[1]);
            assertEquals(inlineCheckoutUnitPriceBeforeExtraction(product),
                    service.getEffectivePrice(product),
                    "price " + valid[0] + " at " + valid[1] + "% must display and charge the same");
        }
    }

    /**
     * The catalog's own boundary adaptation, which is the one thing the
     * extraction did not take over: a missing price or discount reads as zero
     * instead of failing the whole product page. Both columns are
     * {@code NOT NULL} now, so this only reaches rows written before that
     * migration.
     */
    @Test
    void anAbsentPriceOrDiscountIsDisplayedAsZeroRatherThanFailing() {
        assertEquals(new BigDecimal("0.00"), service.getEffectivePrice(pricedProduct(null, "50")));
        assertEquals(new BigDecimal("0.00"), service.getEffectivePrice(pricedProduct(null, null)));
        assertEquals(new BigDecimal("10.00"), service.getEffectivePrice(pricedProduct("10.00", null)));
        assertEquals(new BigDecimal("8.75"), service.getEffectivePrice(pricedProduct("10.00", "12.5")));
    }

    /**
     * A price finer than the money scale is rounded for display rather than
     * refused, which is the only way such a row stays visible to the
     * administrator who can fix it. Checkout still refuses to charge it.
     */
    @Test
    void anOverPrecisePriceIsRoundedForDisplayRatherThanFailing() {
        assertEquals(new BigDecimal("10.01"), service.getEffectivePrice(pricedProduct("10.005", "0")));
        assertEquals(new BigDecimal("5.00"), service.getEffectivePrice(pricedProduct("10.004", "50")));
    }

    /**
     * The remaining leniency a listing has always had, pinned so it is a
     * recorded decision rather than an accident: a negative percentage reads as
     * no discount, and an over-100 percent discount is not capped. Neither can
     * be charged, because {@code ProductPricingPolicy.requireValidDiscount}
     * rejects both and a listing is not what takes the customer's money.
     */
    @Test
    void aListingDoesNotReinterpretADiscountItShouldNeverSee() {
        assertEquals(new BigDecimal("10.00"), service.getEffectivePrice(pricedProduct("10.00", "-5")));
        assertEquals(new BigDecimal("-50.00"), service.getEffectivePrice(pricedProduct("100.00", "150")));
    }

    /** The listing path still prices through the shared policy. */
    @Test
    void aProductListingShowsTheEffectivePrice() {
        when(categoryEntityRepository.existsById(3L)).thenReturn(true);
        when(productEntityRepository.findByCategoryEntityIdAndProductCountGreaterThan(
                eq(3L), eq(0), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pricedProduct("10.01", "12.5"))));

        List<ProductPreviewDto> result = service.getProductsFromCategory(3L, 0, 10);

        assertEquals(1, result.size());
        assertEquals(new BigDecimal("8.76"), result.get(0).getProductPrice());
    }

    /**
     * {@code getEffectivePrice} as it was before the arithmetic moved to
     * {@code ProductPricingPolicy}. Frozen, not refactored.
     */
    private static BigDecimal inlineListingPriceBeforeExtraction(ProductEntity product) {
        BigDecimal discount = product.getProductDiscount() == null ? BigDecimal.ZERO : product.getProductDiscount();
        BigDecimal price = product.getProductPrice() == null ? BigDecimal.ZERO : product.getProductPrice();
        BigDecimal normalizedPrice = price.setScale(2, RoundingMode.HALF_UP);
        if (discount.signum() <= 0) {
            return normalizedPrice;
        }
        BigDecimal factor = BigDecimal.ONE.subtract(discount.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        return normalizedPrice.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * {@code CheckoutCalculations.effectiveUnitPrice} as it was before the
     * extraction, so the display side can be compared with what was charged
     * without reaching into another package.
     */
    private static BigDecimal inlineCheckoutUnitPriceBeforeExtraction(ProductEntity product) {
        BigDecimal price = product.getProductPrice();
        if (price == null || price.signum() < 0 || price.stripTrailingZeros().scale() > 2) {
            throw new GeneralException("Product has an invalid price: " + product.getProductName());
        }
        BigDecimal discount = product.getProductDiscount();
        if (discount == null) {
            discount = BigDecimal.ZERO;
        }
        if (discount.signum() < 0 || discount.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new GeneralException("Product has an invalid discount: " + product.getProductName());
        }

        BigDecimal normalized = price.setScale(2, RoundingMode.HALF_UP);
        if (discount.signum() == 0) {
            return normalized;
        }
        BigDecimal factor = BigDecimal.ONE.subtract(
                discount.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        return normalized.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }

    private ProductEntity pricedProduct(String price, String discount) {
        ProductEntity product = new ProductEntity();
        product.setProductName("Apple");
        product.setProductPrice(price == null ? null : new BigDecimal(price));
        product.setProductDiscount(discount == null ? null : new BigDecimal(discount));
        product.setSubcategoryName("Fruits");
        product.setProductCount(1);
        return product;
    }

    /**
     * The detail read carries the payable price, not only the two stored columns.
     *
     * <p>A client that recombines the stored price and discount in the browser
     * does not reproduce {@link ProductPricingPolicy}'s rounding at the boundaries:
     * 10.10 at 5 percent off is 9.60 here and 9.59 by
     * {@code +(10.10 - 10.10 * 5 / 100).toFixed(2)}. The card, the cart line and
     * the charge are all built from {@link #getEffectivePrice}, so the detail page
     * has to be given the same number rather than the ingredients.
     *
     * <p>The stored columns keep their meaning and are still sent, because the
     * client shows the struck-through original and the percentage from them.
     */
    @Test
    void detailReadCarriesThePayablePriceAlongsideTheStoredColumns() {
        for (String[] valid : VALID_PRODUCTS) {
            ProductEntity product = pricedProduct(valid[0], valid[1]);
            product.setProductDescription("seeded");
            CategoryEntity category = new CategoryEntity();
            category.setId(3L);
            product.setCategoryEntity(category);
            when(productEntityRepository.findById(7L)).thenReturn(Optional.of(product));

            ProductDetailDto detail = service.getProductData(7L);

            assertEquals(service.getEffectivePrice(product), detail.getEffectivePrice(),
                    "price " + valid[0] + " at " + valid[1] + "% must reach the detail reader as the payable price");
            assertEquals(new BigDecimal(valid[0]), detail.getProductPrice(),
                    "the stored price column must keep its existing meaning");
        }
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
