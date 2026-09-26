package com.example.MigrosBackend.dto.payment;

import java.math.BigDecimal;

public record PaymentResponseDto(
        boolean success,
        boolean pending,
        String checkoutId,
        String status,
        String chargeId,
        BigDecimal totalAmount,
        Long amountMinor,
        String currency,
        String error
) {
}
