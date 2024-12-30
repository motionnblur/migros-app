package com.example.MigrosBackend.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProductPreviewDto {
    private Long productId;
    private String productName;
    private String productTitle;
    private float productPrice;
}
