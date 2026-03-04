package com.example.MigrosBackend.exception.user;

public class CategoryNotFoundException extends RuntimeException {
    public CategoryNotFoundException(String categoryId) {
        super(categoryId);
    }
}
