package com.example.MigrosBackend.dto.admin.panel;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProductDescriptionDto {
    private Long productId;
    private String descriptionTabName;
    private String descriptionTabContent;
}
