package com.example.MigrosBackend.repository.product;

import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.service.user.supply.ProductSearchSort;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * The additive query path behind {@code GET /user/supply/searchProducts}.
 *
 * <p>A Spring Data fragment rather than more methods on {@link ProductEntityRepository}
 * because none of this can be expressed as a derived finder or a static
 * {@code @Query}: every filter is optional, the ordering is partly a derived
 * expression, and the same predicate has to be reused by three differently shaped
 * queries. Writing it once against a {@code CriteriaBuilder} is what keeps the
 * page, its total and the subcategory counts describing the same set of
 * products - see {@link ProductSearchSpecifications}.
 */
public interface ProductSearchRepository {

    /**
     * One page of the products matching {@code criteria}, ordered by
     * {@code sort}.
     *
     * <p>The {@code Pageable} carries the page and size bounds only; the
     * ordering is applied by this query rather than by the pageable, because the
     * default sort mode has no property path behind it. The bounds themselves are
     * still {@code PageRequestPolicy}'s, enforced before this is ever called.
     */
    List<ProductEntity> findPage(ProductSearchCriteria criteria, ProductSearchSort sort, Pageable pageable);

    /**
     * How many products match {@code criteria}, ignoring paging.
     *
     * <p>Counted with the same predicate as {@link #findPage}, so the total the
     * client sizes its paginator from describes exactly the set the pages walk.
     * A count derived separately is how a listing ends up claiming three pages
     * and then serving an empty third one.
     */
    long countMatching(ProductSearchCriteria criteria);

    /**
     * How many products match {@code criteria} in each subcategory, ordered by
     * subcategory name.
     *
     * <p>The subcategory selection is deliberately not applied. A count that
     * inherited it would report the selected bucket at its full size and every
     * other bucket at only what survived being subtracted from it, so the figures
     * a customer compares to decide whether switching is worth it would be
     * systematically wrong - with the one they are already looking at being the
     * only plausible one on the page.
     *
     * <p>Empty subcategory names are excluded, matching what
     * {@code countProductsBySubcategory} already does for the existing
     * subcategory listing. An empty name is not something a customer can choose,
     * so counting it would offer a choice that leads nowhere.
     */
    List<SubcategoryCount> countMatchingBySubcategory(ProductSearchCriteria criteria);
}
