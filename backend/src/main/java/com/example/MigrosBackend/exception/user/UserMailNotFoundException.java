package com.example.MigrosBackend.exception.user;

public class UserMailNotFoundException extends RuntimeException {
    public UserMailNotFoundException(String mail) {
        super("User with that email: " + mail + " could not be found.");
    }
}
