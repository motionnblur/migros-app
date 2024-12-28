package com.example.MigrosBackend.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ItemDto2 {
    private String productName;
    private float productPrice;
    private int productCount;
    private float productDiscount;
    private String productDescription;
    private int productCategoryId;
}
