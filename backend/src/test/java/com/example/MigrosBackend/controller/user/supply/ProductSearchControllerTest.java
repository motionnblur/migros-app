package com.example.MigrosBackend.controller.user.supply;

import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.dto.user.product.ProductSearchResponseDto;
import com.example.MigrosBackend.dto.user.product.SubCategoryCountDto;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.exception.user.CategoryNotFoundException;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.supply.ProductSearchAvailability;
import com.example.MigrosBackend.service.user.supply.ProductSearchService;
import com.example.MigrosBackend.service.user.supply.ProductSearchSort;
import com.example.MigrosBackend.service.user.supply.UserCartService;
import com.example.MigrosBackend.service.user.supply.UserSupplyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of {@code GET /user/supply/searchProducts}: how the query
 * string binds, what a bare request means, and what a caller is told when a
 * parameter is wrong.
 *
 * <p>Public reachability is asserted separately in {@code SecurityPublicRoutesTest},
 * which runs the real filter chain; this class has the filters switched off so the
 * binding and the error shapes can be read without a session in the way. What is
 * asserted here that is not asserted there is that <em>every</em> parameter form
 * binds to the value the service is supposed to judge. A mapping that accepted
 * {@code searchProducts} but silently dropped {@code availability} would look public
 * and work wrongly.
 *
 * <p>The refusals are checked for their body as well as their status, because the
 * client branches on the {@code code} and a bare 400 with an unstructured body is
 * the case it cannot handle.
 */
@WebMvcTest(UserSupplyController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProductSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserSupplyService userSupplyService;

    @MockBean
    private UserCartService userCartService;

    @MockBean
    private ProductSearchService productSearchService;

    @MockBean
    private AuthTokenResolver authTokenResolver;

    @MockBean
    private AdminEntityRepository adminEntityRepository;

    @MockBean
    private TokenService tokenService;

    // -------------------------------------------------------------------------
    // Binding
    // -------------------------------------------------------------------------

    /**
     * The bare request. Page 0 and size 10 are applied here rather than in the
     * service, so the defaults live in one place - the request contract - and the
     * service's bounds apply to values that were actually resolved.
     */
    @Test
    void aBareRequestSearchesEverythingWithTheDocumentedDefaults() throws Exception {
        when(productSearchService.search(any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()))
                .thenReturn(emptyResponse());

        mockMvc.perform(get("/user/supply/searchProducts"))
                .andExpect(status().isOk());

        verify(productSearchService).search(
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), eq(0), eq(10));
    }

    @Test
    void everyParameterIsBoundToTheService() throws Exception {
        when(productSearchService.search(any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()))
                .thenReturn(emptyResponse());

        mockMvc.perform(get("/user/supply/searchProducts")
                        .param("q", "milk")
                        .param("categoryId", "7")
                        .param("subcategory", "Dairy")
                        .param("availability", "IN_STOCK")
                        .param("minPrice", "1.50")
                        .param("maxPrice", "20.00")
                        .param("discountedOnly", "true")
                        .param("sort", "PRICE_DESC")
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk());

        verify(productSearchService).search(
                eq("milk"), eq(7L), eq("Dairy"),
                eq(ProductSearchAvailability.IN_STOCK),
                eq(new BigDecimal("1.50")), eq(new BigDecimal("20.00")),
                eq(Boolean.TRUE), eq(ProductSearchSort.PRICE_DESC), eq(2), eq(5));
    }

    /**
     * Every documented spelling of the two enumerations binds.
     *
     * <p>Case-sensitively, which is what Spring does for an enum request parameter
     * and what the contract names: {@code ALL}, {@code IN_STOCK},
     * {@code OUT_OF_STOCK}, {@code DEFAULT}, {@code PRICE_ASC}, {@code PRICE_DESC}.
     * Anything else is refused rather than guessed at - see the refusal tests below -
     * because defaulting an unrecognised value would turn a client typo into a
     * search the user did not ask for with no error to show for it.
     */
    @Test
    void everyDocumentedEnumerationValueBinds() throws Exception {
        when(productSearchService.search(any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()))
                .thenReturn(emptyResponse());

        for (ProductSearchAvailability availability : ProductSearchAvailability.values()) {
            mockMvc.perform(get("/user/supply/searchProducts")
                            .param("availability", availability.name()))
                    .andExpect(status().isOk());
            verify(productSearchService).search(any(), any(), any(), eq(availability),
                    any(), any(), any(), any(), anyInt(), anyInt());
        }

        for (ProductSearchSort sort : ProductSearchSort.values()) {
            mockMvc.perform(get("/user/supply/searchProducts").param("sort", sort.name()))
                    .andExpect(status().isOk());
            verify(productSearchService).search(any(), any(), any(), any(),
                    any(), any(), any(), eq(sort), anyInt(), anyInt());
        }
    }

    /**
     * {@code q} arrives raw and untrimmed. The service owns trimming, because it
     * also owns the length bound - and a layer that trimmed here would make the two
     * rules disagree about what "101 characters" means.
     */
    @Test
    void theSearchTermReachesTheServiceExactlyAsSent() throws Exception {
        when(productSearchService.search(any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()))
                .thenReturn(emptyResponse());

        mockMvc.perform(get("/user/supply/searchProducts").param("q", "  milk  "))
                .andExpect(status().isOk());

        verify(productSearchService).search(eq("  milk  "), any(), any(), any(), any(), any(), any(),
                any(), anyInt(), anyInt());
    }

    /**
     * The discount flag is boxed, so an absent one reaches the service as
     * {@code null} rather than as {@code false}. The service treats those the same;
     * keeping them distinguishable is what lets "absent" stay meaningful if the
     * default ever changes.
     */
    @Test
    void anAbsentDiscountFlagReachesTheServiceAsNull() throws Exception {
        when(productSearchService.search(any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()))
                .thenReturn(emptyResponse());

        mockMvc.perform(get("/user/supply/searchProducts"))
                .andExpect(status().isOk());

        verify(productSearchService).search(any(), any(), any(), any(), any(), any(),
                isNull(), any(), anyInt(), anyInt());
    }

    // -------------------------------------------------------------------------
    // Response shape
    // -------------------------------------------------------------------------

    /**
     * The published shape, field by field. The client reads these names, so they
     * are the contract - and {@code totalItems} in particular has to serialize as a
     * number rather than a string, because it is compared against a page size to
     * work out whether another page exists.
     */
    @Test
    void theResponseShapeIsTheOneTheClientReads() throws Exception {
        ProductPreviewDto product = new ProductPreviewDto();
        product.setProductId(42L);
        product.setProductName("Milk 1L");
        product.setProductPrice(new BigDecimal("12.00"));
        product.setProductCount(8);

        when(productSearchService.search(any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()))
                .thenReturn(new ProductSearchResponseDto(
                        List.of(product), 37L, 2, 10,
                        List.of(new SubCategoryCountDto("Dairy", 11L),
                                new SubCategoryCountDto("Drinks", 26L))));

        mockMvc.perform(get("/user/supply/searchProducts").param("categoryId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(37))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(42))
                .andExpect(jsonPath("$.items[0].productName").value("Milk 1L"))
                .andExpect(jsonPath("$.items[0].productPrice").value(12.00))
                .andExpect(jsonPath("$.items[0].productCount").value(8))
                .andExpect(jsonPath("$.subcategories.length()").value(2))
                .andExpect(jsonPath("$.subcategories[0].subCategoryName").value("Dairy"))
                .andExpect(jsonPath("$.subcategories[0].productCount").value(11))
                .andExpect(jsonPath("$.subcategories[1].subCategoryName").value("Drinks"));
    }

    /**
     * A search that matched nothing is a successful empty answer, not a 404. A
     * customer who searched for something the shop does not stock has to be told so
     * in a shape the client can render, and there is no product to be "not found".
     */
    @Test
    void anEmptyResultIsA200WithAnEmptyListAndARealTotal() throws Exception {
        when(productSearchService.search(any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()))
                .thenReturn(new ProductSearchResponseDto(List.of(), 0L, 0, 10, List.of()));

        mockMvc.perform(get("/user/supply/searchProducts").param("q", "nothing-matches-this"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.subcategories").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Refusals
    // -------------------------------------------------------------------------

    /**
     * Bounds are refused, not clamped. A request for page -1 has to stay
     * distinguishable from a request for page 0, or the client cannot tell that it
     * asked for something impossible.
     */
    @Test
    void outOfRangePagingIsRefusedAtTheHttpLayer() throws Exception {
        mockMvc.perform(get("/user/supply/searchProducts").param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/user/supply/searchProducts").param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/user/supply/searchProducts").param("size", "101"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productSearchService);
    }

    @Test
    void aNonNumericPagingValueIsRefusedWithAStructuredBody() throws Exception {
        mockMvc.perform(get("/user/supply/searchProducts").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/user/supply/searchProducts").param("size", "10.5"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productSearchService);
    }

    @Test
    void aNonNumericCategoryIsRefusedWithAStructuredBody() throws Exception {
        mockMvc.perform(get("/user/supply/searchProducts").param("categoryId", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /**
     * An unknown enumeration value is refused rather than defaulted, in either
     * case. Guessing here would turn a client typo into a search the user did not
     * ask for, with no error to show for it.
     */
    @Test
    void anUnknownAvailabilityOrSortValueIsRefused() throws Exception {
        mockMvc.perform(get("/user/supply/searchProducts").param("availability", "SOME_DAY"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/user/supply/searchProducts").param("sort", "CHEAPEST"))
                .andExpect(status().isBadRequest());

        // The documented spellings are exact. A client sending lower case gets a
        // 400 rather than a silently different search, which is the same failure
        // mode as any other typo.
        mockMvc.perform(get("/user/supply/searchProducts").param("availability", "in_stock"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/user/supply/searchProducts").param("sort", "price_asc"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productSearchService);
    }

    @Test
    void aNonBooleanDiscountFlagIsRefused() throws Exception {
        mockMvc.perform(get("/user/supply/searchProducts").param("discountedOnly", "maybe"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productSearchService);
    }

    /**
     * A negative or over-precise price is refused by the service, which owns the
     * {@code NUMERIC(19, 2)} rules and can say which parameter was wrong.
     */
    @Test
    void aNegativePriceBoundIsRefusedBeforeAnythingIsQueried() throws Exception {
        when(productSearchService.search(any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()))
                .thenThrow(new GeneralException("minPrice cannot be negative"));

        mockMvc.perform(get("/user/supply/searchProducts").param("minPrice", "-1.00"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("minPrice cannot be negative"));
    }

    @Test
    void anOverPrecisePriceBoundIsRefused() throws Exception {
        when(productSearchService.search(any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()))
                .thenThrow(new GeneralException("maxPrice must not exceed two decimal places"));

        mockMvc.perform(get("/user/supply/searchProducts").param("maxPrice", "10.001"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("maxPrice must not exceed two decimal places"));
    }

    /**
     * An inverted band is refused. Left as it is, it would silently match nothing
     * and read as "we have no products in that range" rather than "that range is
     * impossible".
     */
    @Test
    void anInvertedPriceBandIsRefused() throws Exception {
        when(productSearchService.search(any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()))
                .thenThrow(new GeneralException("minPrice must not be greater than maxPrice"));

        mockMvc.perform(get("/user/supply/searchProducts")
                        .param("minPrice", "20.00")
                        .param("maxPrice", "10.00"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("minPrice must not be greater than maxPrice"));
    }

    /**
     * A subcategory without a category reaches the caller as a 400 rather than
     * being dropped, so the client learns about it instead of silently receiving a
     * search over every category.
     */
    @Test
    void aSubcategoryWithoutACategoryReachesTheCallerAsABadRequest() throws Exception {
        when(productSearchService.search(any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()))
                .thenThrow(new GeneralException("subcategory requires categoryId"));

        mockMvc.perform(get("/user/supply/searchProducts").param("subcategory", "Dairy"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("subcategory requires categoryId"));
    }

    /**
     * An unknown category keeps the existing 404 semantics rather than becoming an
     * empty result, so a stale bookmarked category id is reported as such instead
     * of rendering as a category with nothing in it.
     */
    @Test
    void anUnknownCategoryKeepsTheExistingNotFoundSemantics() throws Exception {
        when(productSearchService.search(any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt()))
                .thenThrow(new CategoryNotFoundException("9999"));

        mockMvc.perform(get("/user/supply/searchProducts").param("categoryId", "9999"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("9999"));
    }

    /**
     * The route is a read. A POST is refused outright, so this cannot become the
     * kind of endpoint that changes state while still being reachable by a
     * cross-site link.
     */
    @Test
    void theRouteOnlyAnswersGet() throws Exception {
        mockMvc.perform(post("/user/supply/searchProducts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    private ProductSearchResponseDto emptyResponse() {
        return new ProductSearchResponseDto(List.of(), 0L, 0, 10, List.of());
    }
}
