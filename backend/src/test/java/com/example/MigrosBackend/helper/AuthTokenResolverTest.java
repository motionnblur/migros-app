package com.example.MigrosBackend.helper;

import com.example.MigrosBackend.exception.shared.TokenNotFoundException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthTokenResolverTest {
    private final AuthTokenResolver authTokenResolver = new AuthTokenResolver();

    @Test
    void requireToken_shouldReturnToken_whenValid() {
        String token = "abc";

        String result = authTokenResolver.requireToken(token);

        assertEquals("abc", result);
    }

    @Test
    void requireToken_shouldThrow_whenNull() {
        assertThrows(TokenNotFoundException.class, () -> authTokenResolver.requireToken(null));
    }

    @Test
    void requireToken_shouldThrow_whenBlank() {
        assertThrows(TokenNotFoundException.class, () -> authTokenResolver.requireToken("   "));
    }
}
