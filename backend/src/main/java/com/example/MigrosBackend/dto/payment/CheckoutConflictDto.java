package com.example.MigrosBackend.dto.payment;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Stable typed contract for checkout/payment state conflicts (HTTP 409).
 * Clients branch on {@code code} and {@code pending}, never on message text.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckoutConflictDto(
        String code,
        String message,
        int status,
        boolean pending,
        String checkoutId) {
}
