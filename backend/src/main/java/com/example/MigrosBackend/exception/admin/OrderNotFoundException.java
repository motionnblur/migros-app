package com.example.MigrosBackend.exception.admin;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(String orderId) {
        super(orderId);
    }
}
