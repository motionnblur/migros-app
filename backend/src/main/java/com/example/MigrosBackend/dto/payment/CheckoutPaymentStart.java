package com.example.MigrosBackend.dto.payment;

import java.util.UUID;

public record CheckoutPaymentStart(UUID checkoutId, long amountMinor, String currency) {
}
