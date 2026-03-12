package com.example.MigrosBackend.dto.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupportCustomerSummaryDto {
    private String userMail;
    private String userName;
    private String userLastName;
    private Boolean isBanned;
    private Boolean hasConversation;
}
