package com.example.MigrosBackend.dto.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupportRealtimeEventDto {
    private String type;
    private String userMail;
    private String sender;
    private Long messageId;
}

