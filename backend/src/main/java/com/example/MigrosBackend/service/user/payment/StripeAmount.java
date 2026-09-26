package com.example.MigrosBackend.service.user.payment;

public record StripeAmount(long amountMinor, String currency) {
}
