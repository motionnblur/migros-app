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
}
