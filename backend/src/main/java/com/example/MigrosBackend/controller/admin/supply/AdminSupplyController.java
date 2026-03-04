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
    private ResponseEntity<Void> addCategory(@RequestParam String categoryName) {
        adminSupplyService.addCategory(categoryName);
        return ResponseEntity.ok().build();
    }

    @GetMapping("getProductCountsFromCategory")
    public int getProductCountsFromCategory(@RequestParam Long categoryId) {
        return userSupplyService.getProductCountsFromCategory(categoryId);
    }

    @GetMapping("getProductsFromCategory")
    public ResponseEntity<List<ProductPreviewDto>> getProductsFromCategory(@RequestParam Long categoryId, @RequestParam int page, @RequestParam int productRange) {
        return ResponseEntity.ok(userSupplyService.getProductsFromCategory(categoryId, page, productRange));
    }
}
