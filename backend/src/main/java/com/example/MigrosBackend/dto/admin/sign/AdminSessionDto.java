package com.example.MigrosBackend.dto.admin.sign;

/**
 * Body of {@code GET /admin/session}. The single component keeps the existing
 * JSON shape {@code {"adminName":"..."}}.
 */
public record AdminSessionDto(String adminName) {
}
