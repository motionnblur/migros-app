package com.example.MigrosBackend.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ItemDto {
    private String itemName;
    private int itemCount;
    private float itemPrice;
    private float discount;
    private String categoryName;
    private List<String> itemImageNames;
}
