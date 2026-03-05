package com.example.MigrosBackend.controller.user.supply;

import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDto2;
import com.example.MigrosBackend.dto.user.category.SubCategoryDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.dto.user.product.UserCartItemDto;
import com.example.MigrosBackend.service.user.supply.UserSupplyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("user/supply")
public class UserSupplyController {
    private final UserSupplyService userSupplyService;

    @Autowired
    public UserSupplyController(UserSupplyService userSupplyService) {
        this.userSupplyService = userSupplyService;
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
    public ResponseEntity<Void> addProductToUserCart(@RequestParam Long productId, @RequestParam String token) {
        userSupplyService.addProductToInventory(productId, token);
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
    public ResponseEntity<Void> removeProductFromUserCart(@RequestParam Long productId, @RequestParam String token) {
        userSupplyService.removeProductFromInventory(productId, token);
        return ResponseEntity.ok().build();
    }

    @GetMapping("updateProductCountInUserCart")
    public ResponseEntity<Void> updateProductCountInUserCart(@RequestParam Long productId, @RequestParam int count, @RequestParam String token) {
        userSupplyService.updateProductCountInInventory(productId, count, token);
        return ResponseEntity.ok().build();
    }

    @GetMapping("getAllOrderIds")
    public ResponseEntity<List<Long>> getAllOrderIds(@RequestParam String token) {
        return ResponseEntity.ok(userSupplyService.getAllOrderIds(token));
    }

    @DeleteMapping("cancelOrder")
    public ResponseEntity<Void> cancelOrder(@RequestParam Long orderId, @RequestParam String token) {
        userSupplyService.cancelOrder(orderId, token);
        return ResponseEntity.ok().build();
    }

    @GetMapping("getOrderStatusByOrderId")
    public ResponseEntity<String> getOrderStatusByOrderId(@RequestParam Long orderId, @RequestParam String token) {
        return ResponseEntity.ok(userSupplyService.getOrderStatusByOrderId(orderId, token));
    }
}
