package com.example.MigrosBackend.dto.order;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class OrderPageDto {
    private List<OrderDto> items;
    private long total;
}
