package com.example.MigrosBackend.dto.order;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class OrderDto {
    private long orderId;
    private long orderGroupId;
    private BigDecimal totalPrice;
    private String status;
}
