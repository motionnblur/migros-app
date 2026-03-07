package com.example.MigrosBackend.helper;

import com.example.MigrosBackend.exception.shared.TokenNotFoundException;
import org.springframework.stereotype.Component;

@Component
public class AuthTokenResolver {
    public String requireToken(String token) {
        if (token == null || token.isBlank()) {
            throw new TokenNotFoundException();
        }
        return token;
    }
}
