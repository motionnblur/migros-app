package com.example.MigrosBackend.dto.admin.panel;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DescriptionsDto {
    private Long descriptionId;
    private String descriptionTabName;
    private String descriptionTabContent;

    public DescriptionsDto(Object o, String tab1, String content1) {
        	this.descriptionId = (Long) o;
        	this.descriptionTabName = tab1;
        	this.descriptionTabContent = content1;
    }
}
