package com.example.MigrosBackend.controller.user.supply;

import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.dto.user.category.SubCategoryDto;
import com.example.MigrosBackend.dto.user.order.UserOrderDetailDto;
import com.example.MigrosBackend.dto.user.order.UserOrderGroupDto;
import com.example.MigrosBackend.dto.user.product.ProductDetailDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.dto.user.product.UserCartItemDto;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.service.user.supply.UserCartService;
import com.example.MigrosBackend.service.user.supply.UserSupplyService;
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

import java.util.List;

@RestController
@RequestMapping("/user/supply")
public class UserSupplyController {
    private final UserSupplyService userSupplyService;
    private final UserCartService userCartService;
    private final AuthTokenResolver authTokenResolver;

    @Autowired
    public UserSupplyController(UserSupplyService userSupplyService,
                                UserCartService userCartService,
                                AuthTokenResolver authTokenResolver) {
        this.userSupplyService = userSupplyService;
        this.userCartService = userCartService;
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
    public ResponseEntity<List<ProductPreviewDto>> getProductsFromSubcategory(@RequestParam String subcategoryName,
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

    @PostMapping("addProductToUserCart")
    public ResponseEntity<Void> addProductToUserCart(@RequestParam Long productId) {
        userCartService.addProductToCart(productId, authTokenResolver.requireAuthenticatedUserMail());
        return ResponseEntity.ok().build();
    }

    @GetMapping("getProductData")
    public ResponseEntity<List<UserCartItemDto>> getProductData() {
        return ResponseEntity.ok(userCartService.getCartData(authTokenResolver.requireAuthenticatedUserMail()));
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

