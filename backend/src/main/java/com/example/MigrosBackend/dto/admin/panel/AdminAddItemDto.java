package com.example.MigrosBackend.dto.admin.panel;

import com.example.MigrosBackend.dto.ItemDto;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminAddItemDto {
    private Long adminId;
    private ItemDto itemDto;
}
