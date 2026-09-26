package com.example.MigrosBackend.dto.user.order;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class UserOrderDetailDto {
    private long orderId;
    private long productId;
    private String productName;
    private int count;
    private BigDecimal price;
    private BigDecimal totalPrice;
    private String status;
}
