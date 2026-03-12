package com.example.MigrosBackend.dto.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupportCustomerStatusDto {
    private String userMail;
    private Boolean isBanned;
    private Boolean hasConversation;
}
