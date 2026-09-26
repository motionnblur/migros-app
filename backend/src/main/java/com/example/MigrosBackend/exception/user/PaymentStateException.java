package com.example.MigrosBackend.exception.user;

public class PaymentStateException extends RuntimeException {
    public PaymentStateException(String message) {
        super(message);
    }
}
