package com.example.MigrosBackend.dto.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InternalSupportAgentMessageDto {
    private String userMail;
    private String message;
}
