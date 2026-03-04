package com.example.MigrosBackend.exception.shared;

public class WrongPasswordException extends RuntimeException {
    public WrongPasswordException() {
        super("Wrong password.");
    }
}
