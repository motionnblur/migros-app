package com.example.MigrosBackend.controller.user.supply;

import com.example.MigrosBackend.dto.user.product.ProductDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.dto.user.product.UserCartItemDto;
import com.example.MigrosBackend.service.user.supply.UserSupplyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
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
    @GetMapping("getProductCountsFromSubcategory")
    public ResponseEntity<?> getProductCountsFromSubcategory(@RequestParam String subcategoryName) {
        try {
            return userSupplyService.getProductCountsFromSubcategory(subcategoryName);
        }
        catch (Exception e) {
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
    @GetMapping("addProductToUserCart")
    public ResponseEntity<?> addProductToUserCart(@RequestParam Long productId, @RequestParam String token) {
        try {
            userSupplyService.addProductToInventory(productId, token);
            return new ResponseEntity<>("Product added to inventory", HttpStatus.OK);
        }catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
    @GetMapping("getProductData")
    public ResponseEntity<?> getProductData() throws Exception {
        try{
            List<UserCartItemDto> dto = userSupplyService.getProductData();
            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
    @DeleteMapping("removeProductFromUserCart")
    public ResponseEntity<?> removeProductFromUserCart(@RequestParam Long productId, @RequestParam String token) {
        try {
            userSupplyService.removeProductFromInventory(productId, token);
            return new ResponseEntity<>("Product removed from inventory", HttpStatus.OK);
        }catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
}
