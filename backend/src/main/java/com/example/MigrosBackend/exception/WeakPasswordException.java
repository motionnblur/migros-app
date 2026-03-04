package com.example.MigrosBackend.exception;

public class WeakPasswordException extends RuntimeException {
    public WeakPasswordException() {
        super("Password is not strong enough.");
    }
}