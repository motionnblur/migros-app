package com.example.MigrosBackend.dto.support;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InternalSupportEditAgentMessageDto {
    @NotBlank
    private String userMail;
    @NotBlank
    private String externalMessageId;
    @NotBlank
    @Size(max = 2000)
    private String message;
}
