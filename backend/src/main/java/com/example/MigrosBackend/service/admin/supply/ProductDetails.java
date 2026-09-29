package com.example.MigrosBackend.service.admin.supply;

import java.math.BigDecimal;

/**
 * The product fields an administrator submits, with no transport attached.
 *
 * <p>Three entry points create or edit a product - the JSON
 * {@code /admin/panel/addProduct} body, the multipart
 * {@code /admin/panel/uploadProduct} form, and the multipart
 * {@code /admin/panel/updateProduct} form - and each of them arrives in a
 * different shape: a nested DTO, or eleven positional form parameters. What
 * they mean is identical, so each one is unpacked into this value first and the
 * rules are then applied to the value rather than to the request.
 *
 * <p>It deliberately carries no {@code MultipartFile} and no
 * {@code AdminAddItemDto}. {@link ProductCreationPolicy} is the piece that has to
 * be usable from all three call sites, and a validation helper that imported the
 * multipart or DTO types would force every caller to have those types on its
 * classpath for no gain.
 *
 * <p>The values are raw: they are exactly what the caller sent, before trimming,
 * before an absent discount becomes zero, and before any check. Normalizing and
 * validating produces a second instance, so an unvalidated {@code ProductDetails}
 * can never be mistaken for an acceptable one.
 *
 * @param productName        required, nonblank, at most {@value ProductCreationPolicy#MAX_TEXT_LENGTH} characters
 * @param subCategoryName    required, nonblank, at most {@value ProductCreationPolicy#MAX_TEXT_LENGTH} characters
 * @param productPrice       required, nonnegative major units
 * @param productCount       nonnegative stock
 * @param productDiscount    {@code null} means "no discount" and normalizes to zero
 * @param productDescription optional; {@code null} normalizes to the empty string
 */
record ProductDetails(String productName,
                      String subCategoryName,
                      BigDecimal productPrice,
                      int productCount,
                      BigDecimal productDiscount,
                      String productDescription) {

    static ProductDetails of(String productName,
                             String subCategoryName,
                             BigDecimal productPrice,
                             int productCount,
                             BigDecimal productDiscount,
                             String productDescription) {
        return new ProductDetails(productName, subCategoryName, productPrice, productCount,
                productDiscount, productDescription);
    }
}
