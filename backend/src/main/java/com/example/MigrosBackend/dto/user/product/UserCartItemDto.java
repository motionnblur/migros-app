package com.example.MigrosBackend.dto.user.product;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class UserCartItemDto {
    private Long productId;
    private String productName;
    private BigDecimal productPrice;
    private int productCount;
    private int availableStock;
}
