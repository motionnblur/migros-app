package com.example.MigrosBackend.dto.support;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InternalSupportDeleteAgentMessageDto {
    @NotBlank
    private String userMail;
    @NotBlank
    private String externalMessageId;
}
