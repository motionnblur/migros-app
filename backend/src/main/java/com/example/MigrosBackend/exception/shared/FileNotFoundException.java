package com.example.MigrosBackend.exception.shared;

public class FileNotFoundException extends RuntimeException {
    public FileNotFoundException() {
        super("File not found.");
    }
}
