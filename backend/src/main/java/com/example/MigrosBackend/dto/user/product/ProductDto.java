package com.example.MigrosBackend.dto.user.product;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class ProductDto {
    private String productName;
    private String subCategoryName;
    @PositiveOrZero
    private int productCount;
    @NotNull
    @PositiveOrZero
    private BigDecimal productPrice;
    private BigDecimal productDiscount;
    private String categoryName;
    private List<String> productImageNames;
}
