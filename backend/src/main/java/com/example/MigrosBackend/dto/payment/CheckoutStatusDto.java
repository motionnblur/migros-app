package com.example.MigrosBackend.dto.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CheckoutStatusDto(
        String checkoutId,
        String status,
        BigDecimal totalAmount,
        long amountMinor,
        String currency,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        Long orderGroupId,
        String chargeId
) {
}
