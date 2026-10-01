package com.example.MigrosBackend.repository.product;

import java.math.BigDecimal;

/**
 * The already-validated, already-normalized form of one catalogue search
 * request.
 *
 * <p>Everything here is decided before a query is built. That is deliberate: a
 * value that reaches the database unvalidated is a value whose rejection
 * depends on the driver, and {@code null} used to mean both "no such filter"
 * and "the caller sent nothing useful", which is why each filter is collapsed to
 * an explicit answer here rather than being passed through as a nullable
 * request parameter.
 *
 * <p>The availability filter is stored as two booleans rather than as a three
 * valued enum so the specification has no {@code switch} that can silently grow
 * an unhandled branch. The two are mutually exclusive by construction in
 * {@link #of}.
 *
 * @param namePattern       an escaped {@code LIKE} pattern, or {@code null} for
 *                          no text filter
 * @param categoryId        the category to restrict to, or {@code null} for all
 * @param subcategoryName   an exact subcategory name, or {@code null}
 * @param inStockOnly       keep only rows with {@code productCount > 0}
 * @param outOfStockOnly    keep only rows with {@code productCount <= 0}
 * @param minPrice          inclusive lower bound on the effective price, or
 *                          {@code null}
 * @param maxPrice          inclusive upper bound on the effective price, or
 *                          {@code null}
 * @param discountedOnly    keep only rows whose stored discount is positive
 */
public record ProductSearchCriteria(
        String namePattern,
        Long categoryId,
        String subcategoryName,
        boolean inStockOnly,
        boolean outOfStockOnly,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        boolean discountedOnly) {

    public static ProductSearchCriteria of(String namePattern,
                                           Long categoryId,
                                           String subcategoryName,
                                           boolean inStockOnly,
                                           boolean outOfStockOnly,
                                           BigDecimal minPrice,
                                           BigDecimal maxPrice,
                                           boolean discountedOnly) {
        return new ProductSearchCriteria(namePattern, categoryId, subcategoryName,
                inStockOnly, outOfStockOnly, minPrice, maxPrice, discountedOnly);
    }

    /**
     * The same filters with the subcategory selection removed.
     *
     * <p>Used to build the subcategory counts. A count that included the
     * selected subcategory's own restriction would report the currently selected
     * bucket with its full size while every other bucket reported only what was
     * left of it, so the numbers a customer compares in order to decide whether
     * switching is worth it would be systematically wrong - and the selected
     * one would be the only correct-looking figure on the page.
     *
     * <p>Everything else is kept, so the counts answer "how many products would
     * each bucket hold for the search I am running", which is the question the
     * list is actually for.
     */
    public ProductSearchCriteria withoutSubcategory() {
        return new ProductSearchCriteria(namePattern, categoryId, null,
                inStockOnly, outOfStockOnly, minPrice, maxPrice, discountedOnly);
    }

    /**
     * Whether the counts can be produced at all.
     *
     * <p>Without a category there are no subcategories to switch between, so the
     * response carries an empty list rather than a single set of counts the client
     * would have no way to apply.
     */
    public boolean countsSubcategories() {
        return categoryId != null;
    }
}
