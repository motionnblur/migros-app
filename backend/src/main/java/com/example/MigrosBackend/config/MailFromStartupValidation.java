package com.example.MigrosBackend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fails startup outside exact-local development when the mail sender address is
 * missing. Signup confirmation and password-reset links are only useful if they
 * can leave the system, so a non-local deployment must configure a real sender
 * instead of silently falling back to a shared identity.
 */
@Component
public class MailFromStartupValidation {

    private final AdminStartupProfilePolicy profilePolicy;
    private final String fromAddress;

    public MailFromStartupValidation(AdminStartupProfilePolicy profilePolicy,
                                     @Value("${app.mail.from:}") String fromAddress) {
        this.profilePolicy = profilePolicy;
        this.fromAddress = fromAddress;
    }

    @PostConstruct
    public void validate() {
        boolean missing = fromAddress == null || fromAddress.isBlank();
        if (missing && !profilePolicy.isLocalDevelopment()) {
            throw new IllegalStateException(
                    "APP_MAIL_FROM must be configured outside exact-local development");
        }
    }
}
