package com.example.MigrosBackend.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ItemPreviewDto {
    private Long itemId;
    private String itemImageName;
    private String itemTitle;
    private float itemPrice;
}
