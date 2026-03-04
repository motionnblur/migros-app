package com.example.MigrosBackend.exception;

public class AdminNotFoundException extends RuntimeException {
    public AdminNotFoundException(String adminName) {
        super("Admin with that name: " + adminName + " could not be found.");
    }
}
