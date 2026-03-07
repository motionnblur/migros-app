package com.example.MigrosBackend.dto.order;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OrderDto {
    private long orderId;
    private long orderGroupId;
    private float totalPrice;
    private String status;
}
