package com.example.MigrosBackend.dto.admin.panel;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProductDto2 {
    private String productName;
    private String subCategoryName;
    private float productPrice;
    private int productCount;
    private float productDiscount;
    private String productDescription;
    private int productCategoryId;
}
