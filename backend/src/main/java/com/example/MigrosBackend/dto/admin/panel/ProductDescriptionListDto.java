package com.example.MigrosBackend.dto.admin.panel;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ProductDescriptionListDto {
    private Long productId;
    private List<DescriptionsDto> descriptionList;
}