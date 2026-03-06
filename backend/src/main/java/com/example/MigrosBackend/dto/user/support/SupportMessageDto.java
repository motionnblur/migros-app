package com.example.MigrosBackend.dto.user.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupportMessageDto {
    private Long id;
    private String sender;
    private String message;
    private LocalDateTime createdAt;
}
