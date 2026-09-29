package com.example.MigrosBackend.dto.admin.panel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupportAdminEditMessageDto {
    @NotBlank
    private String userMail;
    @NotBlank
    @Size(max = 2000)
    private String message;
}
