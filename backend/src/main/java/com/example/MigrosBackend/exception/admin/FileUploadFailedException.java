package com.example.MigrosBackend.exception.admin;

public class FileUploadFailedException extends RuntimeException {
    public FileUploadFailedException(String message) {
        super(message);
    }
}
