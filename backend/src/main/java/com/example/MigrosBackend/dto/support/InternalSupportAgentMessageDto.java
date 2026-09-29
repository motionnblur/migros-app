package com.example.MigrosBackend.dto.support;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InternalSupportAgentMessageDto {
    @NotBlank
    private String userMail;
    @NotBlank
    @Size(max = 2000)
    private String message;
    private String externalMessageId;
}
