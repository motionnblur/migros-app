package com.example.MigrosBackend.exception;

public class UserMailCouldNotFoundException extends RuntimeException {
    public UserMailCouldNotFoundException(String mail) {
        super("User with that email: " + mail + " could not be found.");
    }
}
