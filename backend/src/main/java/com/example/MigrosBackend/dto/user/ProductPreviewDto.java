package com.example.MigrosBackend.dto.user;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProductPreviewDto {
    private Long productId;
    private String productName;
    private float productPrice;
}
