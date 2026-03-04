package com.example.MigrosBackend.exception;

public class WrongPasswordException extends RuntimeException {
    public WrongPasswordException() {
        super("Wrong password.");
    }
}
