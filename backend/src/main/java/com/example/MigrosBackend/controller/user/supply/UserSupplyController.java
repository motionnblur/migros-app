package com.example.MigrosBackend.controller.user.supply;

import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.dto.user.category.SubCategoryDto;
import com.example.MigrosBackend.dto.user.order.UserOrderDetailDto;
import com.example.MigrosBackend.dto.user.order.UserOrderGroupDto;
import com.example.MigrosBackend.dto.user.product.CartReconciliationDto;
import com.example.MigrosBackend.dto.user.product.ProductDetailDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.dto.user.product.ProductSearchResponseDto;
import com.example.MigrosBackend.dto.user.product.UserCartItemDto;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.helper.PageRequestPolicy;
import com.example.MigrosBackend.service.user.supply.ProductSearchAvailability;
import com.example.MigrosBackend.service.user.supply.ProductSearchService;
import com.example.MigrosBackend.service.user.supply.ProductSearchSort;
import com.example.MigrosBackend.service.user.supply.UserCartService;
import com.example.MigrosBackend.service.user.supply.UserSupplyService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Anonymous catalogue browsing and the authenticated user order/cart endpoints.
 *
 * <p>Every {@code page}/{@code productRange} pair here is bounded by
 * {@link PageRequestPolicy}. The constraints on the request parameters are the
 * same bound expressed for the HTTP layer: they reject an out-of-range value
 * before the handler runs and reach the caller as a structured
 * {@code ValidationErrorDto} naming the parameter, which
 * {@code GlobalExceptionHandler} already maps to 400. A value that is not an
 * integer at all, or one that overflows {@code int}, fails request-parameter
 * conversion and is answered with 400 by Spring's own type-mismatch handling.
 */
@RestController
@RequestMapping("/user/supply")
public class UserSupplyController {
    private final UserSupplyService userSupplyService;
    private final UserCartService userCartService;
    private final ProductSearchService productSearchService;
    private final AuthTokenResolver authTokenResolver;

    @Autowired
    public UserSupplyController(UserSupplyService userSupplyService,
                                UserCartService userCartService,
                                ProductSearchService productSearchService,
                                AuthTokenResolver authTokenResolver) {
        this.userSupplyService = userSupplyService;
        this.userCartService = userCartService;
        this.productSearchService = productSearchService;
        this.authTokenResolver = authTokenResolver;
    }

    @GetMapping("getAllCategoryNames")
    public ResponseEntity<List<String>> getAllCategoryNames() {
        return ResponseEntity.ok(userSupplyService.getAllCategoryNames());
    }

    @GetMapping("getProductsFromCategory")
    public ResponseEntity<List<ProductPreviewDto>> getProductsFromCategory(@RequestParam Long categoryId,
                                                                           @PositiveOrZero @RequestParam int page,
                                                                           @Min(PageRequestPolicy.MIN_PAGE_SIZE)
                                                                           @Max(PageRequestPolicy.MAX_PAGE_SIZE)
                                                                           @RequestParam int productRange) {
        return ResponseEntity.ok(userSupplyService.getProductsFromCategory(categoryId, page, productRange));
    }

    @GetMapping("getProductsFromSubcategory")
    public ResponseEntity<List<ProductPreviewDto>> getProductsFromSubcategory(@RequestParam String subcategoryName,
                                                                               @PositiveOrZero @RequestParam int page,
                                                                               @Min(PageRequestPolicy.MIN_PAGE_SIZE)
                                                                               @Max(PageRequestPolicy.MAX_PAGE_SIZE)
                                                                               @RequestParam int productRange) {
        return ResponseEntity.ok(userSupplyService.getProductsFromSubcategory(subcategoryName, page, productRange));
    }

    @GetMapping("getProductCountsFromSubcategory")
    public ResponseEntity<Integer> getProductCountsFromSubcategory(@RequestParam String subcategoryName) {
        return ResponseEntity.ok(userSupplyService.getProductCountsFromSubcategory(subcategoryName));
    }

    @GetMapping("getProductCountsFromCategory")
    public ResponseEntity<Integer> getProductCountsFromCategory(@RequestParam Long categoryId) {
        return ResponseEntity.ok(userSupplyService.getProductCountsFromCategory(categoryId));
    }

    @GetMapping("getProductImageNames")
    public ResponseEntity<List<String>> getProductImageNames(@RequestParam Long productId) {
        return ResponseEntity.ok(userSupplyService.getProductImageNames(productId));
    }

    @GetMapping("getProductImage")
    public ResponseEntity<Resource> getProductImage(@RequestParam Long productId) {
        Resource resource = userSupplyService.getProductImage(productId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                .body(resource);
    }

    @GetMapping("getSubCategories")
    public ResponseEntity<List<SubCategoryDto>> getSubCategories(@RequestParam Long categoryId) {
        return ResponseEntity.ok(userSupplyService.getSubCategories(categoryId));
    }

    /**
     * Searches the catalogue by name, category, subcategory, availability, price
     * band, discount and sort order.
     *
     * <p>Every parameter is optional and an absent one means no filter rather
     * than a filter for a default value, so the bare request is the whole
     * catalogue - including sold-out products, because a catalogue that hides
     * them cannot answer "is this still sold here".
     *
     * <p>The {@code page}/{@code size} pair is bounded exactly like every other
     * paging pair in this controller: {@code PageRequestPolicy} rejects an
     * out-of-range value before the service runs, and the parameter annotations
     * express the same bound at the HTTP layer so a bad value arrives as a
     * structured 400 naming the parameter rather than as a clamp. {@code size}
     * defaults to 10 and {@code page} to 0.
     *
     * <p>{@code subcategory} without {@code categoryId} is a 400 rather than a
     * filter that is quietly ignored: subcategory names repeat across categories,
     * so searching one alone would answer with products from categories the
     * customer did not ask about. The price bounds are validated in the service,
     * which owns the {@code NUMERIC(19, 2)} rules and reports which parameter was
     * out of range.
     */
    @GetMapping("searchProducts")
    public ResponseEntity<ProductSearchResponseDto> searchProducts(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String subcategory,
            @RequestParam(required = false) ProductSearchAvailability availability,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Boolean discountedOnly,
            @RequestParam(required = false) ProductSearchSort sort,
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "10")
            @Min(PageRequestPolicy.MIN_PAGE_SIZE)
            @Max(PageRequestPolicy.MAX_PAGE_SIZE)
            int size) {
        return ResponseEntity.ok(productSearchService.search(
                q, categoryId, subcategory, availability, minPrice, maxPrice, discountedOnly,
                sort, page, size));
    }

    @PostMapping("addProductToUserCart")
    public ResponseEntity<Void> addProductToUserCart(@RequestParam Long productId) {
        userCartService.addProductToCart(productId, authTokenResolver.requireAuthenticatedUserMail());
        return ResponseEntity.ok().build();
    }

    @GetMapping("getProductData")
    public ResponseEntity<List<UserCartItemDto>> getProductData() {
        return ResponseEntity.ok(userCartService.getCartData(authTokenResolver.requireAuthenticatedUserMail()));
    }

    /**
     * Repairs the stored cart against what is actually buyable, and reports what
     * it changed.
     *
     * <p>A plain cart read is a pure read and has to stay one: a display request
     * that persisted what it computed was how a concurrent add got erased. But
     * checkout reserves from the stored list, so a read that hides an unbuyable
     * entry while leaving it stored produces a cart that looks complete and then
     * fails at checkout for a line the customer cannot see or remove. This is the
     * explicit, customer-triggered repair for that, and it is a mutation because
     * it changes stored state - which is why it is {@code POST} and not another
     * {@code GET}.
     *
     * <p>The response is the reconciled cart plus the products that were dropped
     * and the ones whose quantity was lowered, so the client can tell the
     * customer exactly what happened rather than silently dropping lines from
     * their order. It reserves nothing, charges nothing and creates no order.
     */
    @PostMapping("reconcileCart")
    public ResponseEntity<CartReconciliationDto> reconcileCart() {
        UserCartService.CartReconciliation reconciliation =
                userCartService.reconcileCart(authTokenResolver.requireAuthenticatedUserMail());
        return ResponseEntity.ok(new CartReconciliationDto(
                reconciliation.cart(),
                reconciliation.removedProductIds(),
                reconciliation.reducedProductIds()));
    }

    @GetMapping("getProductDataWithProductId")
    public ResponseEntity<ProductDetailDto> getProductData(@RequestParam Long productId) {
        return ResponseEntity.ok(userSupplyService.getProductData(productId));
    }

    @GetMapping("getProductDescription")
    public ResponseEntity<ProductDescriptionListDto> getProductDescription(@RequestParam Long productId) {
        return ResponseEntity.ok(userSupplyService.getProductDescription(productId));
    }

    @DeleteMapping("removeProductFromUserCart")
    public ResponseEntity<Void> removeProductFromUserCart(@RequestParam Long productId) {
        userCartService.removeProductFromCart(productId, authTokenResolver.requireAuthenticatedUserMail());
        return ResponseEntity.ok().build();
    }

    @PostMapping("updateProductCountInUserCart")
    public ResponseEntity<Void> updateProductCountInUserCart(@RequestParam Long productId,
                                                             @RequestParam int count) {
        userCartService.updateProductCountInCart(productId, count, authTokenResolver.requireAuthenticatedUserMail());
        return ResponseEntity.ok().build();
    }

    @GetMapping("getAllOrderIds")
    public ResponseEntity<List<Long>> getAllOrderIds() {
        return ResponseEntity.ok(userSupplyService.getAllOrderIds(authTokenResolver.requireAuthenticatedUserMail()));
    }

    @DeleteMapping("cancelOrder")
    public ResponseEntity<Void> cancelOrder(@RequestParam Long orderId) {
        userSupplyService.cancelOrder(orderId, authTokenResolver.requireAuthenticatedUserMail());
        return ResponseEntity.ok().build();
    }

    @GetMapping("getOrderStatusByOrderId")
    public ResponseEntity<String> getOrderStatusByOrderId(@RequestParam Long orderId) {
        return ResponseEntity.ok(userSupplyService.getOrderStatusByOrderId(orderId,
                authTokenResolver.requireAuthenticatedUserMail()));
    }

    @GetMapping("getUserOrders")
    public ResponseEntity<List<UserOrderDetailDto>> getUserOrders() {
        return ResponseEntity.ok(userSupplyService.getUserOrderDetails(authTokenResolver.requireAuthenticatedUserMail()));
    }

    @GetMapping("getUserOrderGroups")
    public ResponseEntity<List<UserOrderGroupDto>> getUserOrderGroups() {
        return ResponseEntity.ok(userSupplyService.getUserOrderGroups(authTokenResolver.requireAuthenticatedUserMail()));
    }
}

