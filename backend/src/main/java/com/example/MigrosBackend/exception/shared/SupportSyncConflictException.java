package com.example.MigrosBackend.exception.shared;

public class SupportSyncConflictException extends RuntimeException {
    public SupportSyncConflictException(String message) {
        super(message);
    }
}
