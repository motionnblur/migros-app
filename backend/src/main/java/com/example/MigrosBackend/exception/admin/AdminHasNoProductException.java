package com.example.MigrosBackend.exception.admin;

public class AdminHasNoProductException extends RuntimeException {
    public AdminHasNoProductException(String adminId) {
        super("Admin with id " + adminId + " has no products.");
    }
}
