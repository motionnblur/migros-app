package com.example.MigrosBackend.dto.admin.panel;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProductDescriptionTabDto(
        Long descriptionId,
        @JsonProperty("descriptionTabName") String tabName,
        @JsonProperty("descriptionTabContent") String tabContent
) {
}
