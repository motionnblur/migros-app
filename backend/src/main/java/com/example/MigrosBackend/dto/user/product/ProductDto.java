package com.example.MigrosBackend.dto.user.product;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ProductDto {
    private String productName;
    private String subCategoryName;
    private int productCount;
    private float productPrice;
    private float productDiscount;
    private String categoryName;
    private List<String> productImageNames;
}
