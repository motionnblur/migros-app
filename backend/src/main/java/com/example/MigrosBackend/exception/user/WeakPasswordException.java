package com.example.MigrosBackend.exception.user;

public class WeakPasswordException extends RuntimeException {
    public WeakPasswordException() {
        super("Password is not strong enough.");
    }
}
