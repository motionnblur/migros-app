package com.example.MigrosBackend.dto.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Stable typed contract for request-validation and framework-level request
 * errors (HTTP 400/409). Clients branch on {@code code}; the {@code errors}
 * list is only present for field-level validation failures.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidationErrorDto(
        String code,
        String message,
        int status,
        List<FieldViolation> errors) {

    public record FieldViolation(String field, String message) {
    }
}
