package com.example.MigrosBackend.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ItemDto {
    private String itemName;
    private int itemCount;
    private float itemPrice;
    private float discount;
    private String categoryName;
}
