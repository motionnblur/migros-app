package com.example.MigrosBackend.dto.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupportMessageEditedEventDto {
    private String eventId;
    private String userMail;
    private String messageId;
    private String text;
}
