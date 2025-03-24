package com.example.MigrosBackend.dto.order;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OrderDto {
    private Long userId;
    private Long itemId;
    private Integer count;
    private Float price;
    private Float totalPrice;
    private String status;
}
