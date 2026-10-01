package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.dto.user.product.ProductSearchResponseDto;
import com.example.MigrosBackend.dto.user.product.SubCategoryCountDto;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catalogue search against a real database rather than a mock repository.
 *
 * <p>Almost nothing about this feature can be established with mocks, and the
 * properties below are exactly the ones a mock cannot show:
 *
 * <ul>
 *   <li><b>Filtering happens before paging.</b> A specification evaluated in
 *       memory after the window is computed has seen one page of rows, so it
 *       produces full pages of products that do not match the filter. These tests
 *       assert that a filtered page is <em>short</em> when fewer products match,
 *       which is only true if the predicate reached the database.</li>
 *   <li><b>Ordering is total.</b> Every mode ends in the primary key, so
 *       adjacent windows cannot share or drop a row and repeated reads of
 *       unchanged data return the same windows.</li>
 *   <li><b>The list, the total and the bucket counts agree.</b> They are built from
 *       one predicate; if that ever stops being true the symptom is a paginator
 *       claiming pages that do not exist.</li>
 * </ul>
 *
 * <p>The {@code LIKE} escaping cases are deliberately names a real catalogue
 * contains - {@code Cola 100%}, {@code Ice_cream}, {@code Wow! Cola}. Unescaped,
 * each quietly widens to a different search: {@code %} becomes "everything",
 * {@code _} becomes "anything containing at least one character", and an unescaped
 * escape character would consume the character after it. All three return the
 * wrong products with no error at all, which is why they are asserted on results.
 *
 * <p>Fixture, with the effective price each row is stored and displayed at:
 *
 * <pre>
 *  # grocery                      subcat    price   disc    effective  stock
 *  1 Fresh Apple 10%              Fruits    20.00   10.00     18.00      5
 *  2 Fresh Apple 20%              Fruits    10.00   20.00      8.00      0
 *  3 Fresh Banana                 Fruits     5.00    0.00      5.00      3
 *  4 Milk 1L                      Dairy     12.00    0.00     12.00      8
 *  5 Yogurt 50%                   Dairy      9.00   50.00      4.50      0
 *  6 Cola 100%                    Drinks     6.00    0.00      6.00     20
 *  7 Ice_cream                    Frozen     7.00    0.00      7.00      4
 *  8 Bread                        Bakery    15.00   33.33     10.00      2
 *  9 Discounted Snack             Snacks     3.00   10.00      2.70      6
 * 10 Half Cent 12.5%              Bakery     1.00   12.50      0.88      3
 * 11 Wow! Cola                    Drinks     4.00    0.00      4.00     12
 *  # electronics
 * 12 Fresh Apple Laptop           Phones   500.00    0.00    500.00      3
 * 13 USB Cable 5%                 Accessories 30.00 5.00      28.50     50
 * </pre>
 *
 * <p>Eleven grocery rows, nine in stock and two out. Two in electronics, both in
 * stock. Row 12 exists so that a text filter which forgot its category constraint
 * would be visible rather than merely assumed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class ProductSearchPostgresTest {

    private static final String USER_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String ADMIN_SECRET = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";

    private static final int GROCERY_ROWS = 11;
    private static final int ALL_ROWS = 13;
    private static final int IN_STOCK_ROWS = 11;
    private static final int OUT_OF_STOCK_ROWS = 2;

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
    private ProductSearchService productSearchService;

    @Autowired
    private UserCatalogReadService catalogReadService;

    @Autowired
    private CategoryEntityRepository categoryEntityRepository;

    @Autowired
    private ProductEntityRepository productEntityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long grocery;
    private Long electronics;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE TABLE product_entity, category_entity RESTART IDENTITY CASCADE");

        grocery = saveCategory("Grocery", 1);
        electronics = saveCategory("Electronics", 2);

        // Seeded in id order, so the DEFAULT sort's expected sequences below read
        // as the fixture does.
        seed(grocery, "Fresh Apple 10%", "Fruits", "20.00", "10.00", 5);
        seed(grocery, "Fresh Apple 20%", "Fruits", "10.00", "20.00", 0);
        seed(grocery, "Fresh Banana", "Fruits", "5.00", "0.00", 3);
        seed(grocery, "Milk 1L", "Dairy", "12.00", "0.00", 8);
        seed(grocery, "Yogurt 50%", "Dairy", "9.00", "50.00", 0);
        seed(grocery, "Cola 100%", "Drinks", "6.00", "0.00", 20);
        seed(grocery, "Ice_cream", "Frozen", "7.00", "0.00", 4);
        seed(grocery, "Bread", "Bakery", "15.00", "33.33", 2);
        seed(grocery, "Discounted Snack", "Snacks", "3.00", "10.00", 6);
        seed(grocery, "Half Cent 12.5%", "Bakery", "1.00", "12.50", 3);
        seed(grocery, "Wow! Cola", "Drinks", "4.00", "0.00", 12);

        seed(electronics, "Fresh Apple Laptop", "Phones", "500.00", "0.00", 3);
        seed(electronics, "USB Cable 5%", "Accessories", "30.00", "5.00", 50);
    }

    // -------------------------------------------------------------------------
    // Defaults and availability
    // -------------------------------------------------------------------------

    /**
     * The bare request is the whole catalogue, sold-out products included.
     *
     * <p>A search that answered the default by hiding sold-out products would make
     * "is this still sold here" unanswerable: the customer finds nothing and cannot
     * tell that apart from the shop simply not stocking it.
     */
    @Test
    void theDefaultSearchIncludesSoldOutProducts() {
        ProductSearchResponseDto response = search(null, null, null, null, null, null, null, null, 0, 20);

        assertEquals(ALL_ROWS, response.totalItems());

        List<String> soldOut = names(search(null, null, null,
                ProductSearchAvailability.OUT_OF_STOCK, null, null, null, null, 0, 20));
        assertEquals(List.of("Fresh Apple 20%", "Yogurt 50%"), soldOut,
                "a sold-out product is a real product and has to be findable");
    }

    @Test
    void theAvailabilityModesPartitionTheWholeCatalogue() {
        assertEquals(ALL_ROWS, availability(ProductSearchAvailability.ALL));
        assertEquals(IN_STOCK_ROWS, availability(ProductSearchAvailability.IN_STOCK));
        assertEquals(OUT_OF_STOCK_ROWS, availability(ProductSearchAvailability.OUT_OF_STOCK));
        assertEquals(ALL_ROWS,
                availability(ProductSearchAvailability.IN_STOCK)
                        + availability(ProductSearchAvailability.OUT_OF_STOCK),
                "no product may fall through both stock filters");
    }

    @Test
    void inStockAndOutOfStockAreExactOpposites() {
        assertEquals(
                List.of("Fresh Apple 10%", "Fresh Banana", "Milk 1L", "Cola 100%", "Ice_cream",
                        "Bread", "Discounted Snack", "Half Cent 12.5%", "Wow! Cola",
                        "Fresh Apple Laptop", "USB Cable 5%"),
                names(search(null, null, null, ProductSearchAvailability.IN_STOCK,
                        null, null, null, null, 0, 20)));
    }

    // -------------------------------------------------------------------------
    // Text search
    // -------------------------------------------------------------------------

    /**
     * Case-insensitive in both directions. A case-sensitive match would silently
     * return nothing for a customer who typed {@code milk} instead of
     * {@code Milk}, with nothing in the response to explain it.
     */
    @Test
    void textSearchIsCaseInsensitiveInBothDirections() {
        assertEquals(1, searchFor("fresh banana").totalItems());
        assertEquals(1, searchFor("FRESH BANANA").totalItems());
        assertEquals(1, searchFor("FrEsH bAnAnA").totalItems());
        assertEquals(1, searchFor("Milk 1L").totalItems());
        assertEquals(1, searchFor("mILK 1l").totalItems());
        assertEquals(4, searchFor("fresh").totalItems(), "three in grocery and one in electronics");
    }

    /**
     * {@code LIKE} metacharacters in the caller's text are data, not syntax.
     *
     * <p>Each of these returns the wrong products - silently - if the term is passed
     * through unescaped, which is why they are asserted on the result set.
     */
    @Test
    void likeWildcardsInTheSearchTermAreEscapedAndMatchedLiterally() {
        assertEquals(List.of("Cola 100%"), names(searchFor("100%")));

        // Six fixture names contain a literal '%'. Unescaped it would match every
        // product in the catalogue instead, because every name has at least one
        // character in it - and it would look like a working search.
        assertEquals(List.of("Fresh Apple 10%", "Cola 100%", "Half Cent 12.5%", "USB Cable 5%",
                        "Fresh Apple 20%", "Yogurt 50%"),
                names(searchFor("%")));
        assertEquals(6, searchFor("%").totalItems(), "and not the " + ALL_ROWS + " products an "
                + "unescaped wildcard would match");

        assertEquals(List.of("Ice_cream"), names(searchFor("_")),
                "an unescaped _ matches any single character, so it matches every product");
        assertEquals(List.of("Ice_cream"), names(searchFor("Ice_cream")));

        assertEquals(List.of("Wow! Cola"), names(searchFor("!")),
                "the escape character has to be escaped too, or it eats the next one");
        assertEquals(List.of("Wow! Cola"), names(searchFor("Wow!")));
    }

    /**
     * A blank term is the unfiltered catalogue, not an error and not a filter that
     * matches nothing. A customer clearing the search box expects the full list.
     */
    @Test
    void aBlankSearchTermMeansNoTextFilterAtAll() {
        assertEquals(ALL_ROWS, searchFor("").totalItems());
        assertEquals(ALL_ROWS, searchFor("   ").totalItems());
        assertEquals(ALL_ROWS, searchFor(null).totalItems());
    }

    @Test
    void aSearchTermLongerThanTheLimitIsRejectedRatherThanTruncated() {
        assertThrows(GeneralException.class,
                () -> search("a".repeat(101), null, null, null, null, null, null, null, 0, 10));

        // Exactly at the limit is accepted; it simply matches nothing here.
        assertEquals(0, search("a".repeat(100), null, null, null, null, null, null, null, 0, 10)
                .totalItems());
    }

    // -------------------------------------------------------------------------
    // Category isolation
    // -------------------------------------------------------------------------

    /**
     * The category constraint is part of the same predicate as the text term, not
     * something applied afterwards. A name that also exists in another category
     * has to stay inside the requested one.
     */
    @Test
    void aCategoryRestrictsTheTextSearchToThatCategory() {
        assertEquals(4, searchFor("fresh").totalItems(), "three in grocery, one in electronics");

        assertEquals(List.of("Fresh Apple 10%", "Fresh Banana", "Fresh Apple 20%"),
                names(search("fresh", grocery, null, null, null, null, null, null, 0, 20)),
                "in stock first, then id ascending");

        assertEquals(List.of("Fresh Apple Laptop"),
                names(search("fresh", electronics, null, null, null, null, null, null, 0, 20)));
    }

    /**
     * A subcategory name that belongs to a different category yields nothing.
     *
     * <p>Not the other category's products. Subcategory names repeat across the
     * catalogue, so a lookup on the name alone answers a different question than
     * the one that was asked.
     */
    @Test
    void aSubcategoryFromAnotherCategoryYieldsAnEmptyResultNotItsProducts() {
        ProductSearchResponseDto response =
                search(null, grocery, "Phones", null, null, null, null, null, 0, 20);

        assertEquals(0, response.totalItems(), "Phones exists, but not in grocery");
        assertTrue(response.items().isEmpty());
        assertEquals(List.of(), names(response));
    }

    @Test
    void anUnknownSubcategoryWithinTheChosenCategoryYieldsAnEmptyResult() {
        assertEquals(0, search(null, grocery, "Nonexistent", null, null, null, null, null, 0, 20)
                .totalItems());
    }

    /**
     * A subcategory without a category is refused rather than applied on its own.
     * Silently dropping the category, or silently applying an unconstrained name,
     * would both return products from categories the customer never named.
     */
    @Test
    void aSubcategoryWithoutACategoryIsRejected() {
        assertThrows(GeneralException.class,
                () -> search(null, null, "Fruits", null, null, null, null, null, 0, 10));
    }

    @Test
    void anUnknownCategoryIsStillNotFoundWithTheExistingSemantics() {
        assertThrows(CategoryNotFoundException.class,
                () -> search(null, grocery + 9999, null, null, null, null, null, null, 0, 10));
    }

    // -------------------------------------------------------------------------
    // Price and discount filters
    // -------------------------------------------------------------------------

    /**
     * Bands are compared against the discounted price - the one the card shows.
     *
     * <p>Compared against the pre-discount column, {@code Fresh Apple 10%} (20.00
     * less 10%, shown at 18.00) would sit outside a band ending at 18.00, so a
     * customer filtering to what they can afford would not see a product whose own
     * card says it is inside that band.
     */
    @Test
    void priceBandsAreComparedAgainstTheEffectivePriceNotTheListedOne() {
        // 18.00 is the most expensive effective price in grocery, and it is a
        // discounted row - which a pre-discount comparison would have placed at 20.00.
        assertEquals(GROCERY_ROWS,
                search(null, grocery, null, null, null, new BigDecimal("18.00"), null, null, 0, 20)
                        .totalItems());
        assertTrue(names(search(null, grocery, null, null, null, new BigDecimal("18.00"), null, null, 0, 20))
                .contains("Fresh Apple 10%"));

        assertEquals(0, search(null, grocery, null, null, new BigDecimal("18.01"), null, null, null, 0, 20)
                .totalItems());

        assertEquals(List.of("Fresh Apple 10%", "Milk 1L", "Bread", "Fresh Apple 20%"),
                names(search(null, grocery, null, null, new BigDecimal("8.00"), null, null, null, 0, 20)),
                "at or above 8.00: Fresh Apple 20% at 8.00, Bread at 10.00, Milk at 12.00, "
                        + "and Fresh Apple 10% at 18.00 - the last of which is only inside the band "
                        + "because the band is compared against 18.00 and not 20.00");

        assertEquals(8, search(null, grocery, null, null, null, new BigDecimal("8.00"), null, null, 0, 20)
                .totalItems(), "at or below 8.00");
    }

    /**
     * Both bounds are inclusive, so a single product sitting exactly on the bound
     * is matched by an equal-valued pair.
     */
    @Test
    void priceBandBoundsAreInclusiveOnBothEnds() {
        assertEquals(List.of("Fresh Apple 20%"), names(
                search(null, grocery, null, null, new BigDecimal("8.00"), new BigDecimal("8.00"),
                        null, null, 0, 20)));
        assertEquals(List.of("Milk 1L"), names(
                search(null, grocery, null, null, new BigDecimal("12.00"), new BigDecimal("12.00"),
                        null, null, 0, 20)));
        assertEquals(List.of("Half Cent 12.5%"), names(
                search(null, grocery, null, null, new BigDecimal("0.88"), new BigDecimal("0.88"),
                        null, null, 0, 20)),
                "0.88 is the policy's rounded half-cent result, so the band has to match it");
    }

    @Test
    void aPriceBandIsRejectedOnlyWhenTheMinimumExceedsTheMaximum() {
        assertThrows(GeneralException.class, () -> search(null, grocery, null, null,
                new BigDecimal("20.00"), new BigDecimal("10.00"), null, null, 0, 10));

        // An inverted range is refused; an equal one is a valid single price.
        assertEquals(List.of("Bread"), names(
                search(null, grocery, null, null, new BigDecimal("10.00"), new BigDecimal("10.00"),
                        null, null, 0, 20)));
        assertEquals(0, search(null, grocery, null, null, new BigDecimal("11.00"), new BigDecimal("11.00"),
                null, null, 0, 20).totalItems());
    }

    @Test
    void discountedOnlySelectsProductsWithAPositiveDiscount() {
        assertEquals(6, search(null, grocery, null, null, null, null, true, null, 0, 20).totalItems());
        assertEquals(List.of("Bread", "Discounted Snack", "Fresh Apple 10%", "Fresh Apple 20%",
                        "Half Cent 12.5%", "Yogurt 50%"),
                names(search(null, grocery, null, null, null, null, true, null, 0, 20)).stream()
                        .sorted().toList());

        assertEquals(GROCERY_ROWS,
                search(null, grocery, null, null, null, null, false, null, 0, 20).totalItems(),
                "false is not a filter either");
        assertEquals(GROCERY_ROWS,
                search(null, grocery, null, null, null, null, null, null, 0, 20).totalItems(),
                "an absent flag is not a filter");
    }

    // -------------------------------------------------------------------------
    // Sorting
    // -------------------------------------------------------------------------

    @Test
    void theDefaultSortPutsInStockProductsFirstThenOrdersById() {
        assertEquals(List.of(
                        "Fresh Apple 10%", "Fresh Banana", "Milk 1L", "Cola 100%", "Ice_cream",
                        "Bread", "Discounted Snack", "Half Cent 12.5%", "Wow! Cola",
                        "Fresh Apple 20%", "Yogurt 50%"),
                names(search(null, grocery, null, null, null, null, null, null, 0, 20)));
    }

    @Test
    void priceSortsOrderByTheEffectivePrice() {
        assertEquals(List.of("Half Cent 12.5%", "Discounted Snack", "Wow! Cola", "Yogurt 50%",
                        "Fresh Banana", "Cola 100%", "Ice_cream", "Fresh Apple 20%", "Bread",
                        "Milk 1L", "Fresh Apple 10%"),
                names(search(null, grocery, null, null, null, null, null,
                        ProductSearchSort.PRICE_ASC, 0, 20)));

        assertEquals(List.of("Fresh Apple 10%", "Milk 1L", "Bread", "Fresh Apple 20%", "Ice_cream",
                        "Cola 100%", "Fresh Banana", "Yogurt 50%", "Wow! Cola", "Discounted Snack",
                        "Half Cent 12.5%"),
                names(search(null, grocery, null, null, null, null, null,
                        ProductSearchSort.PRICE_DESC, 0, 20)));
    }

    /**
     * The tie-breaker. Two products at the same effective price have no defined
     * order without one, and PostgreSQL is free to answer two consecutive page
     * queries differently - which duplicates and drops rows in the listing.
     */
    @Test
    void priceSortsAreStableAcrossRepeatedReadsOfUnchangedData() {
        assertEquals(
                names(search(null, grocery, null, null, null, null, null,
                        ProductSearchSort.PRICE_ASC, 0, 20)),
                names(search(null, grocery, null, null, null, null, null,
                        ProductSearchSort.PRICE_ASC, 0, 20)));
    }

    // -------------------------------------------------------------------------
    // Paging
    // -------------------------------------------------------------------------

    /**
     * The properties a page window has to have: the pages are the same set the
     * total describes, adjacent windows share nothing, and re-reading unchanged
     * data gives the same windows back.
     */
    @Test
    void pagesAreDisjointRepeatableAndAgreeWithTheTotal() {
        int pageSize = 4;

        List<Long> paged = new ArrayList<>();
        for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
            ProductSearchResponseDto response =
                    search(null, grocery, null, null, null, null, null, null, pageNumber, pageSize);
            // 11 rows in pages of 4 is 4, 4, 3 - the last page is short because the
            // set ran out, not because anything was dropped.
            assertEquals(pageNumber == 2 ? 3 : pageSize, response.items().size(),
                    "page " + pageNumber);
            assertEquals(pageNumber, response.page());
            assertEquals(pageSize, response.size());
            assertEquals(GROCERY_ROWS, response.totalItems(),
                    "the total describes the filtered set, not the page");
            paged.addAll(ids(response));
        }

        assertEquals(GROCERY_ROWS, paged.size());
        assertEquals(Set.copyOf(paged).size(), paged.size(), "a row appeared in more than one page");
        assertEquals(
                names(search(null, grocery, null, null, null, null, null, null, 0, 100)),
                paged.stream()
                        .map(id -> nameOf(id))
                        .toList(),
                "paging is a partition of one ordering, so concatenating the windows has to "
                        + "reproduce the whole listing - otherwise a boundary skips or repeats a row. "
                        + "The ordering is the DEFAULT one, which is in stock first and only then id "
                        + "ascending, so it is deliberately not plain id order.");

        List<Long> reread = new ArrayList<>();
        for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
            reread.addAll(ids(search(null, grocery, null, null, null, null, null, null,
                    pageNumber, pageSize)));
        }
        assertEquals(paged, reread, "re-reading unchanged data must return the same windows");
    }

    /**
     * A filtered page is short when fewer products match. This is the property that
     * separates a database filter from an in-memory one: filtering after the
     * window is computed would still hand back a full page here, and only then
     * discard the non-matching rows from it.
     */
    @Test
    void filteringHappensBeforePagingSoAMatchedPageCanBeShort() {
        ProductSearchResponseDto filtered =
                search("Fresh Apple", null, null, null, null, null, null, null, 0, 4);

        assertEquals(3, filtered.totalItems());
        assertEquals(3, filtered.items().size(),
                "a page must never be padded with products that failed the filter");
        assertEquals(List.of("Fresh Apple 10%", "Fresh Apple Laptop", "Fresh Apple 20%"), names(filtered));
    }

    @Test
    void aPageBeyondTheLastOneIsAnEmptySuccessNotAnError() {
        ProductSearchResponseDto response =
                search(null, grocery, null, null, null, null, null, null, 99, 10);

        assertTrue(response.items().isEmpty());
        assertEquals(GROCERY_ROWS, response.totalItems(),
                "the total still describes the whole filtered set");
    }

    /**
     * The bounds are {@link com.example.MigrosBackend.helper.PageRequestPolicy}'s,
     * and an out-of-range value is refused rather than clamped.
     */
    @Test
    void pageAndSizeBoundsAreEnforcedTheSameWayTheExistingListingsEnforceThem() {
        assertThrows(GeneralException.class,
                () -> search(null, grocery, null, null, null, null, null, null, -1, 10));
        assertThrows(GeneralException.class,
                () -> search(null, grocery, null, null, null, null, null, null, 0, 0));
        assertThrows(GeneralException.class,
                () -> search(null, grocery, null, null, null, null, null, null, 0, 101));

        assertEquals(GROCERY_ROWS,
                search(null, grocery, null, null, null, null, null, null, 0, 100).totalItems(),
                "the maximum page size is accepted");
    }

    /**
     * A bad page is reported as a bad page even when the category is also unknown,
     * so the caller learns the thing it can fix rather than only the lookup it
     * cannot.
     */
    @Test
    void anInvalidPageIsRefusedBeforeTheCategoryLookupHappens() {
        assertThrows(GeneralException.class,
                () -> search(null, grocery + 9999, null, null, null, null, null, null, -1, 10));
    }

    // -------------------------------------------------------------------------
    // Price correctness
    // -------------------------------------------------------------------------

    /**
     * The rendered price is the policy's own number, including at the half-cent
     * boundary where a differently-ordered rounding sequence disagrees.
     */
    @Test
    void renderedPricesAreThePricingPolicyNumberAtTheRoundingBoundaries() {
        Map<String, BigDecimal> rendered = search(null, null, null, null, null, null, null, null, 0, 50)
                .items().stream()
                .collect(Collectors.toMap(ProductPreviewDto::getProductName, ProductPreviewDto::getProductPrice));

        assertEquals(new BigDecimal("0.88"), rendered.get("Half Cent 12.5%"),
                "1.00 less 12.5% is 0.875, which rounds away from zero");
        assertEquals(new BigDecimal("10.00"), rendered.get("Bread"),
                "15.00 less 33.33% is 10.0005 once the factor is rounded to six decimals first");
        assertEquals(new BigDecimal("18.00"), rendered.get("Fresh Apple 10%"));
        assertEquals(new BigDecimal("28.50"), rendered.get("USB Cable 5%"));
        assertEquals(new BigDecimal("500.00"), rendered.get("Fresh Apple Laptop"));
    }

    /**
     * The search renders through the same policy the existing catalogue listing
     * uses. A second copy of a discount formula is precisely the defect this
     * feature had to avoid, and it would show up here as a differing price for the
     * same product on two screens of the same shop.
     */
    @Test
    void searchPricesMatchTheExistingCatalogueListingExactly() {
        Map<Long, BigDecimal> fromListing = catalogReadService
                .getProductsFromCategory(grocery, 0, 100).stream()
                .collect(Collectors.toMap(ProductPreviewDto::getProductId, ProductPreviewDto::getProductPrice));
        Map<Long, BigDecimal> fromSearch = search(null, grocery, null,
                        ProductSearchAvailability.IN_STOCK, null, null, null, null, 0, 100)
                .items().stream()
                .collect(Collectors.toMap(ProductPreviewDto::getProductId, ProductPreviewDto::getProductPrice));

        assertFalse(fromListing.isEmpty(), "precondition: the category has in-stock rows");
        assertEquals(fromListing, fromSearch);
    }

    /**
     * Every stored effective price is the policy's result for its row, so the
     * column the search sorts by and the price it renders cannot disagree.
     */
    @Test
    void everyStoredEffectivePriceEqualsThePricingPolicyForItsRow() {
        List<ProductEntity> all = productEntityRepository.findAll();
        assertEquals(ALL_ROWS, all.size());

        for (ProductEntity product : all) {
            assertEquals(0, product.getEffectivePrice().compareTo(ProductPricingPolicy.effectivePrice(
                            product.getProductPrice(), product.getProductDiscount())),
                    "stored effective price for " + product.getProductName() + " drifted from the policy");
        }
    }

    // -------------------------------------------------------------------------
    // Subcategory counts
    // -------------------------------------------------------------------------

    /**
     * Counts are produced only when there is a category, because without one there
     * is nothing to switch between.
     */
    @Test
    void subcategoryCountsAreEmptyWithoutACategory() {
        assertTrue(search(null, null, null, null, null, null, null, null, 0, 10).subcategories().isEmpty());
        assertTrue(search("fresh", null, null, null, null, null, null, null, 0, 10).subcategories().isEmpty());
    }

    @Test
    void subcategoryCountsPartitionTheFilteredResult() {
        ProductSearchResponseDto response = search(null, grocery, null, null, null, null, null, null, 0, 20);

        assertEquals(Map.of("Fruits", 3L, "Dairy", 2L, "Drinks", 2L, "Bakery", 2L,
                "Frozen", 1L, "Snacks", 1L), countsOf(response));
        assertEquals(response.totalItems(), sumOf(response),
                "the buckets have to add up to the total, or the client can trust neither");
    }

    /**
     * The selected subcategory is ignored by the counts. A count that inherited it
     * would show the current bucket at its full size and every other bucket only at
     * what survived the subtraction, which makes switching look like the only option
     * with anything in it.
     */
    @Test
    void subcategoryCountsIgnoreTheSelectedSubcategorySoTheBucketsStayComparable() {
        Map<String, Long> unselected = countsOf(search(null, grocery, null, null, null, null, null, null, 0, 20));
        ProductSearchResponseDto selectingDairy =
                search(null, grocery, "Dairy", null, null, null, null, null, 0, 20);

        assertEquals(2, selectingDairy.totalItems(), "the selection does narrow the list");
        assertEquals(unselected, countsOf(selectingDairy),
                "the counts describe the same buckets whether or not one of them is selected");
        assertEquals(2L, countsOf(selectingDairy).get("Dairy"),
                "including the selected bucket at its own full size");
    }

    @Test
    void subcategoryCountsRespectTheOtherActiveFilters() {
        ProductSearchResponseDto discounted =
                search(null, grocery, null, null, null, null, true, null, 0, 20);
        assertEquals(Map.of("Fruits", 2L, "Dairy", 1L, "Bakery", 2L, "Snacks", 1L), countsOf(discounted));
        assertEquals(6, discounted.totalItems());
        assertEquals(discounted.totalItems(), sumOf(discounted));

        ProductSearchResponseDto priced = search(null, grocery, null, null,
                new BigDecimal("4.00"), new BigDecimal("8.00"), null, null, 0, 20);
        assertEquals(6, priced.totalItems(),
                "Wow! Cola, Yogurt, Fresh Banana, Cola 100%, Ice_cream, Fresh Apple 20%");
        assertEquals(Map.of("Fruits", 2L, "Dairy", 1L, "Drinks", 2L, "Frozen", 1L), countsOf(priced));
        assertEquals(priced.totalItems(), sumOf(priced));
    }

    @Test
    void subcategoryCountsAlsoRespectTheTextFilter() {
        ProductSearchResponseDto response = search("fresh", grocery, null, null, null, null, null, null, 0, 20);

        assertEquals(3, response.totalItems());
        assertEquals(Map.of("Fruits", 3L), countsOf(response),
                "a bucket the text filter emptied must not be offered at all");
    }

    /**
     * An empty subcategory name is not a choice a customer can make, so it is
     * neither listed nor counted - the same rule the existing subcategory listing
     * already follows.
     */
    @Test
    void emptySubcategoryNamesAreNeitherListedNorCounted() {
        seed(grocery, "Nameless Product", "", "5.00", "0.00", 1);

        ProductSearchResponseDto response = search(null, grocery, null, null, null, null, null, null, 0, 20);

        assertEquals(GROCERY_ROWS + 1, response.totalItems(), "the row is an ordinary result");
        assertFalse(countsOf(response).containsKey(""), "but it is not offered as a choice");
        assertEquals(sumOf(response) + 1, response.totalItems(),
                "and the difference is exactly the unnamed row, which stays visible rather than lost");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ProductSearchResponseDto search(String q, Long categoryId, String subcategory,
                                            ProductSearchAvailability availability,
                                            BigDecimal minPrice, BigDecimal maxPrice,
                                            Boolean discountedOnly, ProductSearchSort sort,
                                            int page, int size) {
        return productSearchService.search(q, categoryId, subcategory, availability,
                minPrice, maxPrice, discountedOnly, sort, page, size);
    }

    private ProductSearchResponseDto searchFor(String q) {
        return search(q, null, null, null, null, null, null, null, 0, 20);
    }

    private long availability(ProductSearchAvailability availability) {
        return search(null, null, null, availability, null, null, null, null, 0, 50).totalItems();
    }

    private List<String> names(ProductSearchResponseDto response) {
        return response.items().stream().map(ProductPreviewDto::getProductName).toList();
    }

    private List<Long> ids(ProductSearchResponseDto response) {
        return response.items().stream().map(ProductPreviewDto::getProductId).toList();
    }

    private String nameOf(Long productId) {
        return productEntityRepository.findById(productId).orElseThrow().getProductName();
    }

    private Map<String, Long> countsOf(ProductSearchResponseDto response) {
        return response.subcategories().stream()
                .collect(Collectors.toMap(
                        SubCategoryCountDto::subCategoryName,
                        SubCategoryCountDto::productCount,
                        (first, second) -> first,
                        LinkedHashMap::new));
    }

    private long sumOf(ProductSearchResponseDto response) {
        return response.subcategories().stream().mapToLong(SubCategoryCountDto::productCount).sum();
    }

    private Long saveCategory(String name, int legacyCategoryId) {
        CategoryEntity category = new CategoryEntity();
        category.setCategoryName(name);
        category.setCategoryId(legacyCategoryId);
        return categoryEntityRepository.saveAndFlush(category).getId();
    }

    /**
     * Writes a row the way the production writers do, effective price included.
     * Seeding it through the policy rather than by hand keeps the fixture honest: a
     * hand-written expected value here would only prove the fixture agrees with
     * itself.
     */
    private void seed(Long categoryId, String name, String subcategory,
                      String price, String discount, int stock) {
        ProductEntity product = new ProductEntity();
        product.setProductName(name);
        product.setSubcategoryName(subcategory);
        product.setProductCount(stock);
        product.setProductPrice(new BigDecimal(price));
        product.setProductDiscount(new BigDecimal(discount));
        product.setEffectivePrice(ProductPricingPolicy.effectivePrice(
                new BigDecimal(price), new BigDecimal(discount)));
        product.setProductDescription("seeded");
        product.setCategoryEntity(categoryEntityRepository.getReferenceById(categoryId));
        productEntityRepository.saveAndFlush(product);
    }
}
