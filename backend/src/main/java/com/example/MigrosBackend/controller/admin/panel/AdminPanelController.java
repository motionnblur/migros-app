package com.example.MigrosBackend.controller.admin.panel;

import com.example.MigrosBackend.dto.admin.panel.*;
import com.example.MigrosBackend.dto.order.OrderPageDto;
import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.dto.user.product.ProductDetailDto;
import com.example.MigrosBackend.service.admin.supply.AdminOrderService;
import com.example.MigrosBackend.service.admin.supply.AdminSupplyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/admin/panel")
public class AdminPanelController {
    private final AdminSupplyService adminSupplyService;
    private final AdminOrderService adminOrderService;

    @Autowired
    public AdminPanelController(AdminSupplyService adminSupplyService, AdminOrderService adminOrderService) {
        this.adminSupplyService = adminSupplyService;
        this.adminOrderService = adminOrderService;
    }

    @PostMapping("addProductDescription")
    public ResponseEntity<Void> addProductDescription(@RequestBody ProductDescriptionListDto productDescriptions) {
        adminSupplyService.addProductDescription(productDescriptions);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("deleteProductDescription")
    public ResponseEntity<Void> deleteProductDescription(@RequestParam Long descriptionId) {
        adminSupplyService.deleteProductDescription(descriptionId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("getProductDescription")
    public ResponseEntity<ProductDescriptionListDto> getProductDescription(@RequestParam Long productId) {
        return ResponseEntity.ok(adminSupplyService.getProductDescription(productId));
    }

    @GetMapping("getAllAdminProducts")
    public ResponseEntity<List<AdminProductPreviewDto>> getAllAdminProducts(@RequestParam Long adminId, @RequestParam int page, @RequestParam int productRange) {
        return ResponseEntity.ok(adminSupplyService.getAllAdminProducts(adminId, page, productRange));
    }

    @GetMapping("getProductData")
    public ResponseEntity<ProductDetailDto> getProductData(@RequestParam Long productId) {
        return ResponseEntity.ok(adminSupplyService.getProductData(productId));
    }

    @PostMapping("addProduct")
    public ResponseEntity<Void> addProduct(@Valid @RequestBody AdminAddItemDto adminAddItemDto) {
        adminSupplyService.addProduct(adminAddItemDto);
        return ResponseEntity.ok().build();
    }

    @PostMapping("uploadProduct")
    public ResponseEntity<String> uploadProduct(@NotNull @RequestParam("adminId") Long adminId,
                                                 @RequestParam("productName") String productName,
                                                 @RequestParam("subCategoryName") String subCategoryName,
                                                 @NotNull @PositiveOrZero @RequestParam("productPrice") BigDecimal productPrice,
                                                 @PositiveOrZero @RequestParam("productCount") int productCount,
                                                 @RequestParam("productDiscount") BigDecimal productDiscount,
                                                 @RequestParam("productDescription") String productDescription,
                                                 @RequestParam("selectedImage") MultipartFile selectedImage,
                                                 @RequestParam("categoryValue") int categoryValue) {
        adminSupplyService.uploadProduct(
                adminId, productName, subCategoryName,
                productPrice, productCount, productDiscount,
                productDescription, categoryValue, selectedImage);
        return ResponseEntity.ok("File uploaded successfully");
    }

    @PostMapping("updateProduct")
    public ResponseEntity<String> updateProduct(@NotNull @RequestParam("adminId") Long adminId,
                                                 @NotNull @RequestParam("productId") Long productId,
                                                 @RequestParam("productName") String productName,
                                                 @RequestParam("subCategoryName") String subCategoryName,
                                                 @NotNull @PositiveOrZero @RequestParam("productPrice") BigDecimal productPrice,
                                                 @PositiveOrZero @RequestParam("productCount") int productCount,
                                                 @RequestParam("productDiscount") BigDecimal productDiscount,
                                                 @RequestParam("productDescription") String productDescription,
                                                 @RequestParam(value = "selectedImage", required = false) MultipartFile selectedImage,
                                                 @RequestParam("categoryValue") int categoryValue) {
        adminSupplyService.updateProduct(adminId, productId, productName, subCategoryName, productPrice, productCount, productDiscount, productDescription, categoryValue, selectedImage);
        return ResponseEntity.ok("File uploaded successfully");
    }

    @DeleteMapping("deleteProduct")
    public ResponseEntity<Void> deleteProduct(@RequestParam Long productId) {
        adminSupplyService.deleteProduct(productId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("getAllOrders")
    public ResponseEntity<OrderPageDto> getAllOrders(@RequestParam int page, @RequestParam int productRange) {
        return ResponseEntity.ok(adminOrderService.getAllOrders(page, productRange));
    }

    @GetMapping("getUserProfileData")
    public ResponseEntity<UserProfileTableDto> getUserProfileData(@RequestParam Long orderId) {
        return ResponseEntity.ok(adminOrderService.getUserProfileData(orderId));
    }

    @PostMapping("updateOrderStatus")
    public ResponseEntity<Void> updateOrderStatus(@RequestParam Long orderId, @RequestParam String status) {
        adminOrderService.updateOrderStatus(orderId, status);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("deleteOrder")
    public ResponseEntity<Void> deleteOrder(@RequestParam Long orderId) {
        adminOrderService.deleteOrder(orderId);
        return ResponseEntity.ok().build();
    }
}


