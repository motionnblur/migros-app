package com.example.MigrosBackend.dto.user.category;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SubCategoryDto {
    private Long subCategoryId;
    private String subCategoryName;
    private int productCount;
}
