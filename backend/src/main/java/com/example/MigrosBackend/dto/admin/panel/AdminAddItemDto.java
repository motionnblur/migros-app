package com.example.MigrosBackend.dto.admin.panel;

import com.example.MigrosBackend.dto.user.product.ProductDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminAddItemDto {
    @NotNull
    private Long adminId;
    @NotNull
    @Valid
    private ProductDto productDto;
}
