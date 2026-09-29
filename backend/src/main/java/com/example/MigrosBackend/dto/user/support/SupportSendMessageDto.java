package com.example.MigrosBackend.dto.user.support;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupportSendMessageDto {
    @NotBlank
    @Size(max = 2000)
    private String message;
}
