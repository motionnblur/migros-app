package com.example.MigrosBackend.controller.admin.supply;

import com.example.MigrosBackend.dto.user.product.ProductDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.helper.PageRequestPolicy;
import com.example.MigrosBackend.service.admin.supply.AdminSupplyService;
import com.example.MigrosBackend.service.user.supply.UserSupplyService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin catalogue reads.
 *
 * <p>The paginated reads here serve the same service methods as the anonymous
 * {@code /user/supply} endpoints, so they carry the same
 * {@link PageRequestPolicy} bound and the same structured 400 for a page or
 * range outside it.
 */
@RestController
@RequestMapping("/admin/supply")
public class AdminSupplyController {
    private final AdminSupplyService adminSupplyService;
    private final UserSupplyService userSupplyService;

    @Autowired
    public AdminSupplyController(AdminSupplyService adminSupplyService,
                                 UserSupplyService userSupplyService) {
        this.adminSupplyService = adminSupplyService;
        this.userSupplyService = userSupplyService;
    }

    @PostMapping("addCategory")
    public ResponseEntity<Void> addCategory(@RequestParam String categoryName) {
        adminSupplyService.addCategory(categoryName);
        return ResponseEntity.ok().build();
    }

    @GetMapping("getProductCountsFromCategory")
    public ResponseEntity<Integer> getProductCountsFromCategory(@RequestParam Long categoryId) {
        return ResponseEntity.ok(userSupplyService.getProductCountsFromCategory(categoryId));
    }

    @GetMapping("getProductsFromCategory")
    public ResponseEntity<List<ProductPreviewDto>> getProductsFromCategory(@RequestParam Long categoryId,
                                                                            @PositiveOrZero @RequestParam int page,
                                                                            @Min(PageRequestPolicy.MIN_PAGE_SIZE)
                                                                            @Max(PageRequestPolicy.MAX_PAGE_SIZE)
                                                                            @RequestParam int productRange) {
        return ResponseEntity.ok(userSupplyService.getProductsFromCategory(categoryId, page, productRange));
    }

    @GetMapping("getAllProductCounts")
    public int getAllProductCounts() {
        return userSupplyService.getAllProductCounts();
    }

    @GetMapping("getAllProducts")
    public ResponseEntity<List<ProductPreviewDto>> getAllProducts(@PositiveOrZero @RequestParam int page,
                                                                  @Min(PageRequestPolicy.MIN_PAGE_SIZE)
                                                                  @Max(PageRequestPolicy.MAX_PAGE_SIZE)
                                                                  @RequestParam int productRange) {
        return ResponseEntity.ok(userSupplyService.getAllProducts(page, productRange));
    }
}
