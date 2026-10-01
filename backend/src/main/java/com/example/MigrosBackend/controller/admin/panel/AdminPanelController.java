package com.example.MigrosBackend.controller.admin.panel;

import com.example.MigrosBackend.dto.admin.panel.*;
import com.example.MigrosBackend.dto.order.OrderPageDto;
import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.dto.user.product.ProductDetailDto;
import com.example.MigrosBackend.service.admin.supply.AdminOrderService;
import com.example.MigrosBackend.service.admin.supply.AdminSupplyService;
import com.example.MigrosBackend.helper.PageRequestPolicy;
import com.example.MigrosBackend.helper.ProductEditVersionHeader;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    /**
     * Admin product listing.
     *
     * <p>The page bounds are the same {@link PageRequestPolicy} the anonymous
     * {@code /admin/supply} listing uses, and are declared here so an
     * out-of-range window comes back as a structured {@code VALIDATION_FAILED}
     * naming the offending parameter instead of a 500 from
     * {@code PageRequest.of}, or - worse - a silently oversized {@code LIMIT}.
     */
    @GetMapping("getAllAdminProducts")
    public ResponseEntity<List<AdminProductPreviewDto>> getAllAdminProducts(@RequestParam Long adminId,
                                                                            @PositiveOrZero @RequestParam int page,
                                                                            @Min(PageRequestPolicy.MIN_PAGE_SIZE)
                                                                            @Max(PageRequestPolicy.MAX_PAGE_SIZE)
                                                                            @RequestParam int productRange) {
        return ResponseEntity.ok(adminSupplyService.getAllAdminProducts(adminId, page, productRange));
    }

    @GetMapping("getProductData")
    public ResponseEntity<ProductDetailDto> getProductData(@RequestParam Long productId) {
        return ResponseEntity.ok(adminSupplyService.getProductData(productId));
    }

    /**
     * Creates a product from a JSON body, with no image.
     *
     * <p>Field names are unchanged. The body is validated by the same policy the
     * multipart upload is, so a product created here is as complete as one
     * created with a picture: the description is stored (the empty string when
     * the field is absent) and the category named by {@code categoryName} is
     * resolved to a real category rather than left null.
     *
     * <p>Creating a product without an image is legitimate - the edit path adds
     * the first image to a product that has none, and a version-checked edit can
     * do it - so the absence of {@code selectedImage} here is not an error.
     */
    @PostMapping("addProduct")
    public ResponseEntity<Void> addProduct(@Valid @RequestBody AdminAddItemDto adminAddItemDto) {
        adminSupplyService.addProduct(adminAddItemDto);
        return ResponseEntity.ok().build();
    }

    /**
     * Creates a product together with its image.
     *
     * <p>The {@code @PositiveOrZero} annotations are the request-boundary half of
     * the shared creation policy; the scale, capacity and length rules are applied
     * once, in the service, so the two creation endpoints cannot disagree about
     * them.
     *
     * <p>{@code packageAmount} and {@code packageUnit} are optional and additive:
     * a request that omits both creates a product with no package size, exactly as
     * every product created before V14. They are validated as a pair by the shared
     * policy - a request naming one of them is a 400, not a half-filled row - and
     * the unit is matched case-insensitively before being stored canonically, so
     * {@code "kg"} and {@code " KG "} both mean kilograms.
     */
    @PostMapping("uploadProduct")
    public ResponseEntity<String> uploadProduct(@NotNull @RequestParam("adminId") Long adminId,
                                                 @RequestParam("productName") String productName,
                                                 @RequestParam("subCategoryName") String subCategoryName,
                                                 @NotNull @PositiveOrZero @RequestParam("productPrice") BigDecimal productPrice,
                                                 @PositiveOrZero @RequestParam("productCount") int productCount,
                                                 @RequestParam("productDiscount") BigDecimal productDiscount,
                                                 @RequestParam("productDescription") String productDescription,
                                                 @RequestParam("selectedImage") MultipartFile selectedImage,
                                                 @RequestParam("categoryValue") int categoryValue,
                                                 @RequestParam(value = "packageAmount", required = false) BigDecimal packageAmount,
                                                 @RequestParam(value = "packageUnit", required = false) String packageUnit) {
        adminSupplyService.uploadProduct(
                adminId, productName, subCategoryName,
                productPrice, productCount, productDiscount,
                productDescription, categoryValue, packageAmount, packageUnit,
                selectedImage);
        return ResponseEntity.ok("File uploaded successfully");
    }

    /**
     * Edits an existing product.
     *
     * <p>{@code expectedVersion} is required, and required is the point: the
     * editor submits the version it loaded, so a form that has been open since
     * before a checkout reserved stock is rejected with 409 instead of writing
     * its stale absolute count over the current one. There is deliberately no
     * default and no inference from a missing value - silently accepting a
     * missing version would make the guard optional exactly when a caller is
     * too old to send it.
     *
     * <p>Absent gives 400 {@code MISSING_PARAMETER}, non-numeric gives 400
     * {@code VALIDATION_FAILED}, negative gives 400 {@code VALIDATION_FAILED},
     * and stale gives 409 {@code PRODUCT_EDIT_CONFLICT}. The path, the method
     * and the success body are unchanged.
     *
     * <p>A success additionally carries {@code X-Product-Version}: the version
     * this transaction produced. It is additive, and it is the only version the
     * editor may pair with the values it just submitted. Re-reading the product
     * afterwards to obtain it would be a second, later read that can observe a
     * version that a checkout reservation has already moved past, and the stale
     * form values would then be written back against it.
     *
     * <p>An edit that changes only the package metadata goes through the identical
     * contract: the same required {@code expectedVersion}, the same 409 for a stale
     * one, the same {@code X-Product-Version} on success. Package size is stored on
     * the product row, so an edit that moves it must advance the version - a form
     * that held a stale size would otherwise be undetectable.
     */
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
                                                 @RequestParam("categoryValue") int categoryValue,
                                                 @RequestParam(value = "packageAmount", required = false) BigDecimal packageAmount,
                                                 @RequestParam(value = "packageUnit", required = false) String packageUnit,
                                                 @NotNull @PositiveOrZero @RequestParam("expectedVersion") Long expectedVersion) {
        long resultingVersion = adminSupplyService.updateProduct(adminId, productId, productName, subCategoryName, productPrice, productCount, productDiscount, productDescription, categoryValue, packageAmount, packageUnit, selectedImage, expectedVersion);
        return ResponseEntity.ok()
                .header(ProductEditVersionHeader.NAME, Long.toString(resultingVersion))
                .body("File uploaded successfully");
    }

    @DeleteMapping("deleteProduct")
    public ResponseEntity<Void> deleteProduct(@RequestParam Long productId) {
        adminSupplyService.deleteProduct(productId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("getAllOrders")
    public ResponseEntity<OrderPageDto> getAllOrders(@PositiveOrZero @RequestParam int page,
                                                      @Min(PageRequestPolicy.MIN_PAGE_SIZE)
                                                      @Max(PageRequestPolicy.MAX_PAGE_SIZE)
                                                      @RequestParam int productRange) {
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


