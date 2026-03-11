package com.example.MigrosBackend.dto.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupportCustomerMessageCreatedEventDto {
    private String eventId;
    private String conversationId;
    private String customerId;
    private String messageId;
    private String text;
    private String occurredAt;
}
