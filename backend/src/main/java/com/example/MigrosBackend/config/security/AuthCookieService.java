package com.example.MigrosBackend.config.security;

import com.example.MigrosBackend.service.global.TokenService;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class AuthCookieService {
    private final AuthCookieProperties properties;
    private final TokenService tokenService;

    public AuthCookieService(AuthCookieProperties properties, TokenService tokenService) {
        this.properties = properties;
        this.tokenService = tokenService;
    }

    public ResponseCookie createUserSessionCookie(String token) {
        return buildSessionCookie(AuthCookies.USER_SESSION_COOKIE_NAME, token, "/");
    }

    public ResponseCookie createAdminSessionCookie(String token) {
        return buildSessionCookie(AuthCookies.ADMIN_SESSION_COOKIE_NAME, token, "/admin");
    }

    public ResponseCookie clearUserSessionCookie() {
        return clearSessionCookie(AuthCookies.USER_SESSION_COOKIE_NAME, "/");
    }

    public ResponseCookie clearAdminSessionCookie() {
        return clearSessionCookie(AuthCookies.ADMIN_SESSION_COOKIE_NAME, "/admin");
    }

    private ResponseCookie buildSessionCookie(String name, String token, String path) {
        return ResponseCookie.from(name, token)
                .httpOnly(true)
                .secure(properties.isSecure())
                .path(path)
                .sameSite(properties.getSameSite())
                .maxAge(Duration.ofMillis(tokenService.getTokenTtlMillis()))
                .build();
    }

    private ResponseCookie clearSessionCookie(String name, String path) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(properties.isSecure())
                .path(path)
                .sameSite(properties.getSameSite())
                .maxAge(Duration.ZERO)
                .build();
    }
}
