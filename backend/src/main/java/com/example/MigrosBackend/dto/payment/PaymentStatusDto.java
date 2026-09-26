package com.example.MigrosBackend.dto.payment;

import java.math.BigDecimal;

public record PaymentStatusDto(
        String checkoutId,
        String attemptId,
        String checkoutStatus,
        String state,
        String chargeId,
        BigDecimal totalAmount,
        Long amountMinor,
        String currency,
        Long orderGroupId,
        boolean finalized,
        boolean pending,
        boolean refunded
) {
}
