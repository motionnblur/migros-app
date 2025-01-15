package com.example.MigrosBackend.controller.user;

import com.example.MigrosBackend.dto.user.ProductPreviewDto;
import com.example.MigrosBackend.service.user.UserSupplyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    public List<String> getAllCategoryNames() {
        return userSupplyService.getAllCategoryNames();
    }

    @GetMapping("getProductsFromCategory")
    public ResponseEntity<?> getProductsFromCategory(@RequestParam Long categoryId, @RequestParam int page, @RequestParam int productRange) {
        try {
            List<ProductPreviewDto> itemIDs = userSupplyService.getProductsFromCategory(categoryId, page, productRange);
            return ResponseEntity.ok(itemIDs);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
    @GetMapping("getProductsFromSubcategory")
    public ResponseEntity<?> getSubProductsFromCategory(@RequestParam String subcategoryName, @RequestParam int page, @RequestParam int productRange) {
        try {
            List<ProductPreviewDto> itemIDs = userSupplyService.getProductsFromSubcategory(subcategoryName, page, productRange);
            return ResponseEntity.ok(itemIDs);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
    @GetMapping("getProductCountsFromCategory")
    public ResponseEntity<?> getProductCountsFromCategory(@RequestParam Long categoryId) {
        try {
            return userSupplyService.getProductCountsFromCategory(categoryId);
        }
        catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    @GetMapping("getProductImageNames")
    public List<String> getProductImageNames(@RequestParam Long productId) {
        return userSupplyService.getProductImageNames(productId);
    }
    @GetMapping("getProductImage")
    public ResponseEntity<Resource> getProductImage(@RequestParam Long productId) throws Exception {
        return userSupplyService.getProductImage(productId);
    }
    @GetMapping("getSubCategories")
    public ResponseEntity<?> getSubCategories(@RequestParam Long categoryId) {
        try {
            return userSupplyService.getSubCategories(categoryId);
        }catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }

    }
}
