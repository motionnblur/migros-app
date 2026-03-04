package com.example.MigrosBackend.exception.admin;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String userId) {
        super(userId);
    }
}
