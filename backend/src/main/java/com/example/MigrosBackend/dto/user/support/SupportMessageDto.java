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
    private LocalDateTime editedAt;

    public SupportMessageDto(Long id, String sender, String message, LocalDateTime createdAt) {
        this.id = id;
        this.sender = sender;
        this.message = message;
        this.createdAt = createdAt;
    }
}
