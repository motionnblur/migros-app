package com.example.MigrosBackend.config.security;

import com.example.MigrosBackend.config.AdminStartupProfilePolicy;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthCookieStartupConfiguration {

    /**
     * Refuses to start a non-local deployment that would issue session cookies
     * without the {@code Secure} attribute.
     *
     * <p>{@link AuthCookieProperties#isSecure()} defaults to {@code false} so the
     * hybrid local workflow works over plain HTTP. Outside exact-local
     * development that default silently downgrades both the user and the admin
     * session cookie, which is a credential over a cleartext channel. The guard
     * mirrors the default-administrator guard: it runs after repositories and
     * beans are initialized but before the web server accepts traffic, and it
     * follows the same centralized exact-local policy so the two cannot
     * disagree.
     */
    @Bean
    public SmartInitializingSingleton nonLocalInsecureCookieGuard(AuthCookieProperties authCookieProperties,
                                                                  AdminStartupProfilePolicy profilePolicy) {
        return () -> {
            if (profilePolicy.isLocalDevelopment()) {
                return;
            }
            if (!authCookieProperties.isSecure()) {
                throw new IllegalStateException(
                        "Refusing to start: auth.cookie.secure=false outside exact-local development. "
                                + "Session cookies must be Secure in a non-local environment; "
                                + "set AUTH_COOKIE_SECURE=true (or auth.cookie.secure=true).");
            }
        };
    }
}
