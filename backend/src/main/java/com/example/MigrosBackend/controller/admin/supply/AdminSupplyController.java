package com.example.MigrosBackend.controller.admin.supply;

import com.example.MigrosBackend.dto.user.product.ProductDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.service.admin.supply.AdminSupplyService;
import com.example.MigrosBackend.service.user.supply.UserSupplyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("admin/supply")
public class AdminSupplyController {
    private final AdminSupplyService adminSupplyService;
    private final UserSupplyService userSupplyService;

    @Autowired
    public AdminSupplyController(AdminSupplyService adminSupplyService,
                                 UserSupplyService userSupplyService) {
        this.adminSupplyService = adminSupplyService;
        this.userSupplyService = userSupplyService;
    }

    @GetMapping("addCategory")
    private ResponseEntity<?> addCategory(@RequestParam String categoryName) throws Exception {
        try{
            adminSupplyService.addCategory(categoryName);
            return new ResponseEntity<>(HttpStatus.OK);
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
    @GetMapping("getProductsFromCategory")
    public ResponseEntity<?> getProductsFromCategory(@RequestParam Long categoryId, @RequestParam int page, @RequestParam int productRange) {
        try {
            List<ProductPreviewDto> itemIDs = userSupplyService.getProductsFromCategory(categoryId, page, productRange);
            return ResponseEntity.ok(itemIDs);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
    @PostMapping("addProduct")
    private ResponseEntity<?> addItem(@RequestBody ProductDto productDto) throws Exception {
        try{
            adminSupplyService.addProduct(productDto);
            return new ResponseEntity<>(HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
}
