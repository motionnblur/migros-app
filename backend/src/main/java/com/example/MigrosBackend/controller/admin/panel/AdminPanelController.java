package com.example.MigrosBackend.controller.admin.panel;

import com.example.MigrosBackend.dto.admin.panel.AdminAddItemDto;
import com.example.MigrosBackend.dto.admin.panel.AdminProductPreviewDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.service.admin.supply.AdminSupplyService;
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

    @Autowired
    public AdminPanelController(AdminSupplyService adminSupplyService) {
        this.adminSupplyService = adminSupplyService;
    }

    @PostMapping("addProductDescription")
    private ResponseEntity<?> addProductDescription(@RequestBody ProductDescriptionListDto productDescriptions) throws Exception {
        try{
            adminSupplyService.addProductDescription(productDescriptions);
            return new ResponseEntity<>(HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
    @DeleteMapping("deleteProductDescription")
    private ResponseEntity<?> deleteProductDescription(@RequestParam Long descriptionId) throws Exception {
        try{
            adminSupplyService.deleteProductDescription(descriptionId);
            return new ResponseEntity<>(HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
    @GetMapping("getProductDescription")
    private ResponseEntity<?> getProductDescription(@RequestParam Long productId) throws Exception {
        try{
            ProductDescriptionListDto productDescriptionDto = adminSupplyService.getProductDescription(productId);
            return ResponseEntity.ok(productDescriptionDto);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
    @GetMapping("getAllAdminProducts")
    private ResponseEntity<?> getAllAdminProducts(@RequestParam Long adminId, @RequestParam int page, @RequestParam int productRange) throws Exception {
        // Process the product data here
        try{
            List<AdminProductPreviewDto> allAdminProducts = adminSupplyService.getAllAdminProducts(adminId, page, productRange);
            return ResponseEntity.ok(allAdminProducts);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
    @GetMapping("getProductData")
    private ResponseEntity<?> getProductData(@RequestParam Long productId) throws Exception {
        try{
            return ResponseEntity.ok(adminSupplyService.getProductData(productId));
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
    @PostMapping("addProduct")
    private ResponseEntity<?> addProduct(@RequestBody AdminAddItemDto adminAddItemDto) throws Exception {
        try{
            adminSupplyService.addProduct(adminAddItemDto);
            return new ResponseEntity<>(HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
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
        // Save the file to your desired location
        try {
            adminSupplyService.uploadProduct(adminId, productName, subCategoryName, productPrice,
                    productCount, productDiscount, productDescription,
                    categoryValue, selectedImage);
            return ResponseEntity.ok("File uploaded successfully");
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
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
        // Save the file to your desired location
        try {
            adminSupplyService.updateProduct(adminId, productId, productName, subCategoryName,
                    productPrice, productCount, productDiscount,
                    productDescription, categoryValue, selectedImage);
            return ResponseEntity.ok("File uploaded successfully");
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
    @DeleteMapping("deleteProduct")
    private ResponseEntity<?> deleteProduct(@RequestParam Long productId) throws Exception {
        try{
            adminSupplyService.deleteProduct(productId);
            return new ResponseEntity<>(HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
}
