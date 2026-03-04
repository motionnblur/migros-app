package com.example.MigrosBackend.exception;

public class TokenNotFoundException extends RuntimeException {
    public TokenNotFoundException() {
        super("Token not found or expired.");
    }
}