package com.example.MigrosBackend.dto.admin.panel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupportAdminEditMessageDto {
    private String userMail;
    private String message;
}
