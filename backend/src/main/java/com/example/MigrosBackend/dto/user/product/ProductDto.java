package com.example.MigrosBackend.dto.user.product;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class ProductDto {
    private String productName;
    private String subCategoryName;
    private int productCount;
    private BigDecimal productPrice;
    private BigDecimal productDiscount;
    private String categoryName;
    private List<String> productImageNames;
}
