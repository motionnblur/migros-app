package com.example.MigrosBackend.dto.user.order;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserOrderDetailDto {
    private long orderId;
    private long productId;
    private String productName;
    private int count;
    private float price;
    private float totalPrice;
    private String status;
}
