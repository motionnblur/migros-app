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

    public ResponseCookie createSessionCookie(String token) {
        return ResponseCookie.from(AuthCookies.SESSION_COOKIE_NAME, token)
                .httpOnly(true)
                .secure(properties.isSecure())
                .path("/")
                .sameSite(properties.getSameSite())
                .maxAge(Duration.ofMillis(tokenService.getTokenTtlMillis()))
                .build();
    }

    public ResponseCookie clearSessionCookie() {
        return ResponseCookie.from(AuthCookies.SESSION_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(properties.isSecure())
                .path("/")
                .sameSite(properties.getSameSite())
                .maxAge(Duration.ZERO)
                .build();
    }
}
