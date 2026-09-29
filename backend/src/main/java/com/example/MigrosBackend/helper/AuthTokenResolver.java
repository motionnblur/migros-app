package com.example.MigrosBackend.helper;

import com.example.MigrosBackend.exception.shared.TokenNotFoundException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class AuthTokenResolver {
    public String requireToken(String token) {
        if (token == null || token.isBlank()) {
            throw new TokenNotFoundException();
        }
        return token;
    }

    /**
     * Resolves the authenticated caller's subject (the user mail for user
     * routes) from the security context {@code JwtRequestFilter} populated.
     *
     * <p>This is the single identity resolution point for user-facing
     * controllers; the raw session token is never re-parsed or re-validated
     * here. It fails closed when no authenticated principal is present, so the
     * existing {@link TokenNotFoundException} outcome is preserved.
     */
    public String requireAuthenticatedUserMail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            throw new TokenNotFoundException();
        }
        return authentication.getName();
    }
}
