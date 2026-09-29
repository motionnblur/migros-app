package com.example.MigrosBackend.dto.user.product;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class ProductDetailDto {
    private String productName;
    private String subCategoryName;
    private BigDecimal productPrice;
    private int productCount;
    private BigDecimal productDiscount;
    private String productDescription;
    private int productCategoryId;

    /**
     * The edit version the reader observed, for a subsequent product update.
     *
     * <p>Additive for catalog readers: existing fields and their meaning are
     * unchanged, and nothing else in the app consumes this. It is what lets an
     * admin edit form detect that stock moved under it instead of overwriting
     * it with a stale absolute count.
     */
    private Long productVersion;
}
