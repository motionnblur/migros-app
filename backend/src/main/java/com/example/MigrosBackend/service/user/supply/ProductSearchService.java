package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.user.product.ProductSearchResponseDto;
import com.example.MigrosBackend.dto.user.product.SubCategoryCountDto;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.exception.user.CategoryNotFoundException;
import com.example.MigrosBackend.helper.PageRequestPolicy;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import com.example.MigrosBackend.repository.product.ProductSearchCriteria;
import com.example.MigrosBackend.repository.product.ProductSearchRepository;
import com.example.MigrosBackend.repository.product.ProductSearchSpecifications;
import com.example.MigrosBackend.repository.product.SubcategoryCount;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Catalogue search: a filtered, sorted, paged product list plus the subcategory
 * counts the client needs to offer the remaining choices.
 *
 * <p>Additive and separate from {@link UserCatalogReadService}. Nothing here
 * changes what the existing catalogue endpoints return, and those keep their own
 * queries and their own semantics. This exists because a catalogue listing that
 * can only be paged by category cannot answer a question as ordinary as "what do
 * you have under twenty lira and in stock" - and answering it by loading rows
 * into memory and filtering them would paginate <em>before</em> filtering, so the
 * pages would be full of products that do not match and the total would describe
 * a different set than the list.
 *
 * <p>The order of the work below is the contract, not an implementation detail:
 *
 * <ol>
 *   <li>Validate and normalize the scalars. No database access, so a malformed
 *       request costs nothing.</li>
 *   <li>Build the {@link Pageable}, which is where {@code page}/{@code size} are
 *       bounded - still before any query, and still a rejection rather than a
 *       silent clamp.</li>
 *   <li>Resolve the category, which is the one filter that can be answered with
 *       a lookup: an unknown category is the existing 404, not an empty page.</li>
 *   <li>Run the page, the total and the counts.</li>
 * </ol>
 *
 * <p>The step order is what makes a request that is wrong in two ways fail for
 * the reason the caller can act on: a bad page is reported as a bad page even
 * when the category is also unknown, instead of being masked by the 404.
 *
 * <p>What a result row contains is not decided here either. Each page row is
 * projected by {@code UserCatalogReadService}, the same projection the existing
 * category and subcategory listings use, so a search result and a category listing
 * are byte-identical for the same product - including its price, its package size
 * and its unit price. What this class owns is the <em>set</em> of rows a request
 * matches and their order, not what a row says about itself.
 */
@Service
public class ProductSearchService {

    /**
     * The longest accepted search term.
     *
     * <p>Rejected rather than truncated. A silently truncated term searches for
     * something the customer did not ask for and reports a total for it, and
     * there is nothing in the response that would tell them the term had been
     * altered.
     */
    static final int MAX_QUERY_LENGTH = 100;

    /**
     * The longest accepted subcategory name.
     *
     * <p>{@code subcategory_name} is {@code VARCHAR(255)}, so anything longer
     * cannot match a stored row. Bounding it here keeps that a 400 the caller can
     * act on rather than a shape of request that silently returns nothing.
     */
    static final int MAX_SUBCATEGORY_LENGTH = 255;

    /** The scale of {@code NUMERIC(19, 2)} money, which the price bounds share. */
    private static final int MONEY_SCALE = 2;

    private final ProductSearchRepository productSearchRepository;
    private final CategoryEntityRepository categoryEntityRepository;
    private final UserCatalogReadService catalogReadService;

    @Autowired
    public ProductSearchService(ProductSearchRepository productSearchRepository,
                                CategoryEntityRepository categoryEntityRepository,
                                UserCatalogReadService catalogReadService) {
        this.productSearchRepository = productSearchRepository;
        this.categoryEntityRepository = categoryEntityRepository;
        this.catalogReadService = catalogReadService;
    }

    /**
     * Runs one catalogue search.
     *
     * @param q              a product-name substring, or {@code null}/blank for
     *                       no text filter
     * @param categoryId     a category to restrict to, or {@code null} for all
     * @param subcategory    an exact subcategory name; only meaningful together
     *                       with {@code categoryId}
     * @param availability   {@link ProductSearchAvailability#ALL} when absent
     * @param minPrice       inclusive lower bound on the effective price
     * @param maxPrice       inclusive upper bound on the effective price
     * @param discountedOnly {@code null} or {@code false} for no discount filter
     * @param sort           {@link ProductSearchSort#DEFAULT} when absent
     * @param page           zero-based page index
     * @param size           page size
     */
    @Transactional(readOnly = true)
    public ProductSearchResponseDto search(String q,
                                           Long categoryId,
                                           String subcategory,
                                           ProductSearchAvailability availability,
                                           BigDecimal minPrice,
                                           BigDecimal maxPrice,
                                           Boolean discountedOnly,
                                           ProductSearchSort sort,
                                           int page,
                                           int size) {
        // Step 1: scalars only. Nothing here touches the database.
        String namePattern = normalizeSearchText(q);
        String subcategoryName = normalizeSubcategory(subcategory, categoryId);
        BigDecimal minimumPrice = requirePriceBound("minPrice", minPrice);
        BigDecimal maximumPrice = requirePriceBound("maxPrice", maxPrice);
        requireOrderedPriceRange(minimumPrice, maximumPrice);

        ProductSearchAvailability requestedAvailability =
                availability == null ? ProductSearchAvailability.ALL : availability;
        ProductSearchSort requestedSort = sort == null ? ProductSearchSort.DEFAULT : sort;
        boolean discounted = discountedOnly != null && discountedOnly;

        // Step 2: the page bounds, still before any query. An out-of-range page
        // is rejected rather than clamped, so "asked and refused" stays
        // distinguishable from "asked and got it".
        Pageable pageable = PageRequestPolicy.of(page, size);

        // Step 3: the only filter that is a lookup rather than a predicate.
        if (categoryId != null && !categoryEntityRepository.existsById(categoryId)) {
            throw new CategoryNotFoundException(categoryId.toString());
        }

        ProductSearchCriteria criteria = ProductSearchCriteria.of(
                namePattern,
                categoryId,
                subcategoryName,
                requestedAvailability == ProductSearchAvailability.IN_STOCK,
                requestedAvailability == ProductSearchAvailability.OUT_OF_STOCK,
                minimumPrice,
                maximumPrice,
                discounted);

        // Step 4: the page, its total, and the counts. Same predicate, so the
        // three answers describe the same set.
        List<ProductEntity> pageContent =
                productSearchRepository.findPage(criteria, requestedSort, pageable);
        long totalItems = productSearchRepository.countMatching(criteria);
        List<SubCategoryCountDto> subcategories = criteria.countsSubcategories()
                ? productSearchRepository.countMatchingBySubcategory(criteria).stream()
                        .map(this::toSubCategoryCountDto)
                        .toList()
                : List.of();

        return new ProductSearchResponseDto(
                pageContent.stream().map(catalogReadService::toProductPreviewDto).toList(),
                totalItems,
                page,
                size,
                subcategories);
    }

    private SubCategoryCountDto toSubCategoryCountDto(SubcategoryCount count) {
        return new SubCategoryCountDto(count.subcategoryName(), count.productCount());
    }

    /**
     * Turns a caller's search term into a {@code LIKE} pattern, or into nothing.
     *
     * <p>A blank term is not an error and is not a filter: a customer who clears
     * the search box expects the unfiltered catalogue back, and rejecting the
     * empty string would make the field impossible to clear.
     */
    private String normalizeSearchText(String q) {
        String trimmed = q == null ? "" : q.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            throw new GeneralException("q must not exceed " + MAX_QUERY_LENGTH + " characters");
        }
        return ProductSearchSpecifications.toLikePattern(trimmed);
    }

    /**
     * Normalizes the subcategory selection, and refuses one without a category.
     *
     * <p>A subcategory name is not unique across the catalogue, so on its own it
     * selects products from every category that happens to contain it. Returning
     * those would be an answer to a different question than the one asked, so the
     * combination is refused instead of silently dropping one of the two filters.
     */
    private String normalizeSubcategory(String subcategory, Long categoryId) {
        String trimmed = subcategory == null ? "" : subcategory.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (categoryId == null) {
            throw new GeneralException("subcategory requires categoryId");
        }
        if (trimmed.length() > MAX_SUBCATEGORY_LENGTH) {
            throw new GeneralException(
                    "subcategory must not exceed " + MAX_SUBCATEGORY_LENGTH + " characters");
        }
        return trimmed;
    }

    /**
     * A price band boundary: nonnegative and at most two decimal places.
     *
     * <p>The scale bound is the schema's own, and the sign matters because the
     * filter is a comparison - a negative bound silently matches everything
     * rather than matching nothing, which is the opposite of what a customer
     * typing a negative number would expect and impossible to distinguish from a
     * working filter afterwards.
     */
    private BigDecimal requirePriceBound(String parameterName, BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw new GeneralException(parameterName + " cannot be negative");
        }
        if (value.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw new GeneralException(parameterName + " must not exceed two decimal places");
        }
        return value;
    }

    private void requireOrderedPriceRange(BigDecimal minPrice, BigDecimal maxPrice) {
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new GeneralException("minPrice must not be greater than maxPrice");
        }
    }
}
