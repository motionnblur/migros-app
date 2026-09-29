package com.example.MigrosBackend.dto.user.sign;

/**
 * Body of {@code GET /user/session}. The single component keeps the existing
 * JSON shape {@code {"userMail":"..."}}.
 */
public record UserSessionDto(String userMail) {
}
