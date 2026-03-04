package com.example.MigrosBackend.controller.admin.panel;

import com.example.MigrosBackend.dto.admin.panel.*;
import com.example.MigrosBackend.dto.order.OrderDto;
import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.service.admin.supply.AdminSupplyService;
import com.example.MigrosBackend.service.user.supply.UserOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("admin/panel")
public class AdminPanelController {
    private final AdminSupplyService adminSupplyService;
    private final UserOrderService userOrderService;

    @Autowired
    public AdminPanelController(AdminSupplyService adminSupplyService, UserOrderService userOrderService) {
        this.adminSupplyService = adminSupplyService;
        this.userOrderService = userOrderService;
    }

    @PostMapping("addProductDescription")
    private ResponseEntity<Void> addProductDescription(@RequestBody ProductDescriptionListDto productDescriptions) {
        adminSupplyService.addProductDescription(productDescriptions);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("deleteProductDescription")
    private ResponseEntity<Void> deleteProductDescription(@RequestParam Long descriptionId) {
        adminSupplyService.deleteProductDescription(descriptionId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("getProductDescription")
    private ResponseEntity<ProductDescriptionListDto> getProductDescription(@RequestParam Long productId) {
        return ResponseEntity.ok(adminSupplyService.getProductDescription(productId));
    }

    @GetMapping("getAllAdminProducts")
    private ResponseEntity<List<AdminProductPreviewDto>> getAllAdminProducts(@RequestParam Long adminId, @RequestParam int page, @RequestParam int productRange) {
        return ResponseEntity.ok(adminSupplyService.getAllAdminProducts(adminId, page, productRange));
    }

    @GetMapping("getProductData")
    private ResponseEntity<ProductDto2> getProductData(@RequestParam Long productId) {
        return ResponseEntity.ok(adminSupplyService.getProductData(productId));
    }

    @PostMapping("addProduct")
    private ResponseEntity<Void> addProduct(@RequestBody AdminAddItemDto adminAddItemDto) {
        adminSupplyService.addProduct(adminAddItemDto);
        return ResponseEntity.ok().build();
    }

    @PostMapping("uploadProduct")
    private ResponseEntity<String> uploadProduct(@RequestParam("adminId") Long adminId,
                                                 @RequestParam("productName") String productName,
                                                 @RequestParam("subCategoryName") String subCategoryName,
                                                 @RequestParam("productPrice") float productPrice,
                                                 @RequestParam("productCount") int productCount,
                                                 @RequestParam("productDiscount") float productDiscount,
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
    private ResponseEntity<String> updateProduct(@RequestParam("adminId") Long adminId,
                                                 @RequestParam("productId") Long productId,
                                                 @RequestParam("productName") String productName,
                                                 @RequestParam("subCategoryName") String subCategoryName,
                                                 @RequestParam("productPrice") float productPrice,
                                                 @RequestParam("productCount") int productCount,
                                                 @RequestParam("productDiscount") float productDiscount,
                                                 @RequestParam("productDescription") String productDescription,
                                                 @RequestParam("selectedImage") MultipartFile selectedImage,
                                                 @RequestParam("categoryValue") int categoryValue) {
        adminSupplyService.updateProduct(adminId, productId, productName, subCategoryName, productPrice, productCount, productDiscount, productDescription, categoryValue, selectedImage);
        return ResponseEntity.ok("File uploaded successfully");
    }

    @DeleteMapping("deleteProduct")
    private ResponseEntity<Void> deleteProduct(@RequestParam Long productId) {
        adminSupplyService.deleteProduct(productId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("getAllOrders")
    public ResponseEntity<List<OrderDto>> getOrder(@RequestParam int page, @RequestParam int productRange) {
        return ResponseEntity.ok(userOrderService.getAllOrders(page, productRange));
    }

    @GetMapping("getUserProfileData")
    public ResponseEntity<UserProfileTableDto> getUserProfileData(@RequestParam Long orderId) {
        return ResponseEntity.ok(userOrderService.getUserProfileData(orderId));
    }

    @GetMapping("updateOrderStatus")
    public ResponseEntity<Void> updateOrderStatus(@RequestParam Long orderId, @RequestParam String status) {
        userOrderService.updateOrderStatus(orderId, status);
        return ResponseEntity.ok().build();
    }
}
