package com.example.MigrosBackend.repository.product;

import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.service.user.supply.ProductSearchSort;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link ProductSearchCriteria} into the one predicate and the one
 * ordering that the product page, its total count and the subcategory counts are
 * all built from.
 *
 * <p>Single source on purpose. Those three answers come from three different
 * queries, and if they were written three times they would drift the way every
 * hand-written filter pair here has drifted before: the count would say 40 while
 * the list paginates over 37, or the subcategory counts would quietly ignore the
 * price band the list is showing. The list and the count are therefore not
 * written twice but once, here, and the three call sites differ only in what
 * they select.
 *
 * <p>Everything is SQL. There is no in-memory filtering anywhere in this path:
 * a filter applied after the page window is computed has seen only the rows of
 * one page, so it yields full pages of results that do not match the filter and
 * a total describing a different set than the list does.
 *
 * <p>No absent filter emits a "match nothing" placeholder. A predicate for an
 * absent filter has to be expressed as a constant that is guaranteed true, and
 * the cheapest way to write that is to get it subtly wrong; an absent filter
 * here simply contributes no predicate at all.
 */
public final class ProductSearchSpecifications {

    /**
     * The {@code LIKE} metacharacter that escapes another one.
     *
     * <p>Not the backslash. {@code \} inside a JDBC string literal is a
     * portability trap: it depends on the driver's string-literal escaping
     * rather than on SQL, so the same annotation can produce a different
     * statement on a different driver. A punctuation character that carries no
     * meaning in SQL or in a regular expression removes that whole class of
     * surprise.
     */
    static final char LIKE_ESCAPE_CHARACTER = '!';

    private ProductSearchSpecifications() {
    }

    /**
     * The predicate for {@code criteria}.
     *
     * @param criteria           the validated filters
     * @param includeSubcategory whether the selected subcategory narrows the
     *                           result. {@code false} for the counts, which must
     *                           show every bucket the other filters allow rather
     *                           than only the unselected ones.
     */
    public static Predicate toPredicate(Root<ProductEntity> root, CriteriaBuilder builder,
                                        ProductSearchCriteria criteria,
                                        boolean includeSubcategory) {
        List<Predicate> predicates = new ArrayList<>();

        addNamePredicate(criteria, root, builder, predicates);
        addCategoryPredicate(criteria, root, builder, predicates);
        if (includeSubcategory) {
            addSubcategoryPredicate(criteria, root, builder, predicates);
        }
        addAvailabilityPredicate(criteria, root, builder, predicates);
        addPricePredicates(criteria, root, builder, predicates);
        addDiscountPredicate(criteria, root, builder, predicates);

        return predicates.isEmpty()
                ? builder.conjunction()
                : builder.and(predicates.toArray(Predicate[]::new));
    }

    /**
     * Escapes {@code LIKE} metacharacters in caller-supplied text and wraps it in
     * wildcards.
     *
     * <p>{@code 100%} is a perfectly ordinary product name. Passed through
     * unescaped it becomes a pattern matching every product whose name ends in
     * {@code 00}, which is a search that silently returns the wrong products -
     * worse than failing, because the caller has no way to tell. {@code _} is
     * escaped for the same reason: it matches any single character, so an
     * unescaped one quietly turns a precise search into a fuzzy one.
     *
     * <p>Escaping happens here, once, on the way in, rather than being left to
     * each caller. This is the only shape a caller-supplied string takes before
     * it reaches the database.
     */
    public static String toLikePattern(String rawText) {
        String trimmed = rawText.trim();
        StringBuilder pattern = new StringBuilder(trimmed.length() + 4);
        pattern.append('%');
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            if (character == LIKE_ESCAPE_CHARACTER || character == '%' || character == '_') {
                pattern.append(LIKE_ESCAPE_CHARACTER);
            }
            pattern.append(character);
        }
        return pattern.append('%').toString();
    }

    /**
     * The ordering for a sort mode.
     *
     * <p>Every mode ends in the primary key, which is the deterministic
     * tie-breaker that makes page windows disjoint: two products at the same
     * effective price, and two sold-out products, have no defined order without
     * it, and PostgreSQL is free to return them in a different order for two
     * consecutive page queries. That duplicates and drops rows in the listing
     * the client renders.
     *
     * <p>Spelled out in criteria rather than delegated to a {@code Sort}: the
     * default ordering sorts by a derived boolean that has no column behind it,
     * and a sort object can only name property paths. Expressing it here also
     * means the ordering is applied explicitly to the query that is being run,
     * instead of being appended by a repository method to whatever else it
     * decided the ordering should be.
     */
    public static List<Order> ordersOf(Root<ProductEntity> root, CriteriaBuilder builder,
                                       ProductSearchSort sort) {
        return switch (sort) {
            case DEFAULT -> {
                // In stock first, then by id. A sold-out product is still a real
                // product the customer may want to see, so it is not filtered
                // out here - it is ordered behind what can actually be bought.
                Expression<Integer> inStockFirst = builder.<Integer>selectCase()
                        .when(builder.greaterThan(root.get("productCount"), 0), builder.literal(0))
                        .otherwise(builder.literal(1));
                yield List.of(builder.asc(inStockFirst), builder.asc(root.get("id")));
            }
            case PRICE_ASC -> List.of(
                    builder.asc(root.get("effectivePrice")),
                    builder.asc(root.get("id")));
            case PRICE_DESC -> List.of(
                    builder.desc(root.get("effectivePrice")),
                    builder.asc(root.get("id")));
        };
    }

    private static void addNamePredicate(ProductSearchCriteria criteria, Root<ProductEntity> root,
                                         CriteriaBuilder builder, List<Predicate> predicates) {
        if (criteria.namePattern() == null) {
            return;
        }
        // Both sides lowered in the database, so the comparison happens entirely
        // in the database's own case-folding rules. Lowering the pattern in Java
        // would use the JVM's rules instead, and those disagree for some
        // locales - which would make "apple" match or fail to match depending on
        // where the server happens to run.
        Expression<String> name = builder.lower(root.get("productName"));
        Expression<String> pattern = builder.lower(builder.literal(criteria.namePattern()));
        predicates.add(builder.like(name, pattern, LIKE_ESCAPE_CHARACTER));
    }

    private static void addCategoryPredicate(ProductSearchCriteria criteria, Root<ProductEntity> root,
                                             CriteriaBuilder builder, List<Predicate> predicates) {
        if (criteria.categoryId() == null) {
            return;
        }
        predicates.add(builder.equal(root.get("categoryEntity").get("id"), criteria.categoryId()));
    }

    private static void addSubcategoryPredicate(ProductSearchCriteria criteria, Root<ProductEntity> root,
                                                CriteriaBuilder builder, List<Predicate> predicates) {
        if (criteria.subcategoryName() == null) {
            return;
        }
        // Exact, and only ever combined with a category. Matching on the name
        // alone is the defect the category filter exists to prevent: "Fruits"
        // exists in more than one category, and an unconstrained subcategory
        // search would answer with products from the category the customer did
        // not ask about.
        predicates.add(builder.equal(root.get("subcategoryName"), criteria.subcategoryName()));
    }

    private static void addAvailabilityPredicate(ProductSearchCriteria criteria, Root<ProductEntity> root,
                                                CriteriaBuilder builder, List<Predicate> predicates) {
        if (criteria.inStockOnly()) {
            predicates.add(builder.greaterThan(root.get("productCount"), 0));
        }
        if (criteria.outOfStockOnly()) {
            predicates.add(builder.lessThanOrEqualTo(root.get("productCount"), 0));
        }
    }

    private static void addPricePredicates(ProductSearchCriteria criteria, Root<ProductEntity> root,
                                           CriteriaBuilder builder, List<Predicate> predicates) {
        // effective_price, not product_price. A band expressed in the prices a
        // customer sees has to be compared against the prices a customer sees,
        // and those are the discounted ones. Comparing against the pre-discount
        // column would place a discounted product outside its own price band.
        if (criteria.minPrice() != null) {
            predicates.add(builder.greaterThanOrEqualTo(root.get("effectivePrice"), criteria.minPrice()));
        }
        if (criteria.maxPrice() != null) {
            predicates.add(builder.lessThanOrEqualTo(root.get("effectivePrice"), criteria.maxPrice()));
        }
    }

    private static void addDiscountPredicate(ProductSearchCriteria criteria, Root<ProductEntity> root,
                                             CriteriaBuilder builder, List<Predicate> predicates) {
        if (criteria.discountedOnly()) {
            predicates.add(builder.greaterThan(root.get("productDiscount"), 0));
        }
    }
}
