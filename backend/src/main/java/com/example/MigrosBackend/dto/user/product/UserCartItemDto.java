package com.example.MigrosBackend.dto.user.product;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserCartItemDto {
    private Long productId;
    private String productName;
    private float productPrice;
    private int productCount;
}
