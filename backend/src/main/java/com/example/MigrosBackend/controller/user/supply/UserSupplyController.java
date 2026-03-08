package com.example.MigrosBackend.controller.user.supply;

import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDto2;
import com.example.MigrosBackend.dto.user.category.SubCategoryDto;
import com.example.MigrosBackend.dto.user.order.UserOrderDetailDto;
import com.example.MigrosBackend.dto.user.order.UserOrderGroupDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.dto.user.product.UserCartItemDto;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.service.user.supply.UserSupplyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("user/supply")
public class UserSupplyController {
    private final UserSupplyService userSupplyService;
    private final AuthTokenResolver authTokenResolver;

    @Autowired
    public UserSupplyController(UserSupplyService userSupplyService, AuthTokenResolver authTokenResolver) {
        this.userSupplyService = userSupplyService;
        this.authTokenResolver = authTokenResolver;
    }

    @GetMapping("getAllCategoryNames")
    public ResponseEntity<List<String>> getAllCategoryNames() {
        return ResponseEntity.ok(userSupplyService.getAllCategoryNames());
    }

    @GetMapping("getProductsFromCategory")
    public ResponseEntity<List<ProductPreviewDto>> getProductsFromCategory(@RequestParam Long categoryId,
                                                                           @RequestParam int page,
                                                                           @RequestParam int productRange) {
        return ResponseEntity.ok(userSupplyService.getProductsFromCategory(categoryId, page, productRange));
    }

    @GetMapping("getProductsFromSubcategory")
    public ResponseEntity<List<ProductPreviewDto>> getSubProductsFromCategory(@RequestParam String subcategoryName,
                                                                              @RequestParam int page,
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

    @GetMapping("addProductToUserCart")
    public ResponseEntity<Void> addProductToUserCart(@RequestParam Long productId,
                                                     @CookieValue(name = AuthCookies.USER_SESSION_COOKIE_NAME, required = false) String token) {
        userSupplyService.addProductToInventory(productId, authTokenResolver.requireToken(token));
        return ResponseEntity.ok().build();
    }

    @GetMapping("getProductData")
    public ResponseEntity<List<UserCartItemDto>> getProductData() {
        return ResponseEntity.ok(userSupplyService.getProductData());
    }

    @GetMapping("getProductDataWithProductId")
    private ResponseEntity<ProductDto2> getProductData(@RequestParam Long productId) {
        return ResponseEntity.ok(userSupplyService.getProductData(productId));
    }

    @GetMapping("getProductDescription")
    private ResponseEntity<ProductDescriptionListDto> getProductDescription(@RequestParam Long productId) {
        return ResponseEntity.ok(userSupplyService.getProductDescription(productId));
    }

    @DeleteMapping("removeProductFromUserCart")
    public ResponseEntity<Void> removeProductFromUserCart(@RequestParam Long productId,
                                                          @CookieValue(name = AuthCookies.USER_SESSION_COOKIE_NAME, required = false) String token) {
        userSupplyService.removeProductFromInventory(productId, authTokenResolver.requireToken(token));
        return ResponseEntity.ok().build();
    }

    @GetMapping("updateProductCountInUserCart")
    public ResponseEntity<Void> updateProductCountInUserCart(@RequestParam Long productId,
                                                             @RequestParam int count,
                                                             @CookieValue(name = AuthCookies.USER_SESSION_COOKIE_NAME, required = false) String token) {
        userSupplyService.updateProductCountInInventory(productId, count, authTokenResolver.requireToken(token));
        return ResponseEntity.ok().build();
    }

    @GetMapping("getAllOrderIds")
    public ResponseEntity<List<Long>> getAllOrderIds(
            @CookieValue(name = AuthCookies.USER_SESSION_COOKIE_NAME, required = false) String token) {
        return ResponseEntity.ok(userSupplyService.getAllOrderIds(authTokenResolver.requireToken(token)));
    }

    @DeleteMapping("cancelOrder")
    public ResponseEntity<Void> cancelOrder(@RequestParam Long orderId,
                                            @CookieValue(name = AuthCookies.USER_SESSION_COOKIE_NAME, required = false) String token) {
        userSupplyService.cancelOrder(orderId, authTokenResolver.requireToken(token));
        return ResponseEntity.ok().build();
    }

    @GetMapping("getOrderStatusByOrderId")
    public ResponseEntity<String> getOrderStatusByOrderId(@RequestParam Long orderId,
                                                          @CookieValue(name = AuthCookies.USER_SESSION_COOKIE_NAME, required = false) String token) {
        return ResponseEntity.ok(userSupplyService.getOrderStatusByOrderId(orderId, authTokenResolver.requireToken(token)));
    }

    @GetMapping("getUserOrders")
    public ResponseEntity<List<UserOrderDetailDto>> getUserOrders(
            @CookieValue(name = AuthCookies.USER_SESSION_COOKIE_NAME, required = false) String token) {
        return ResponseEntity.ok(userSupplyService.getUserOrderDetails(authTokenResolver.requireToken(token)));
    }

    @GetMapping("getUserOrderGroups")
    public ResponseEntity<List<UserOrderGroupDto>> getUserOrderGroups(
            @CookieValue(name = AuthCookies.USER_SESSION_COOKIE_NAME, required = false) String token) {
        return ResponseEntity.ok(userSupplyService.getUserOrderGroups(authTokenResolver.requireToken(token)));
    }
}
