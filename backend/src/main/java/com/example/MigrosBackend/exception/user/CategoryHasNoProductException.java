package com.example.MigrosBackend.exception.user;

public class CategoryHasNoProductException extends RuntimeException {
    public CategoryHasNoProductException(String categoryId) {
        super(categoryId);
    }
}
