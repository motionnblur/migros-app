package com.example.MigrosBackend.exception.user;

public class CheckoutNotFoundException extends RuntimeException {
    public CheckoutNotFoundException() {
        super("Checkout not found");
    }
}
