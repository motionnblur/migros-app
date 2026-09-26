package com.example.MigrosBackend.exception.user;

public class PaymentAmountException extends RuntimeException {
    public PaymentAmountException(String message) {
        super(message);
    }
}
