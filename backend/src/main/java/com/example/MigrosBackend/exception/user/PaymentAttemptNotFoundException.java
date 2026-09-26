package com.example.MigrosBackend.exception.user;

public class PaymentAttemptNotFoundException extends RuntimeException {
    public PaymentAttemptNotFoundException() {
        super("Payment attempt not found");
    }
}
