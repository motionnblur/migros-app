package com.example.MigrosBackend.helper;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@Component
public class PasswordValidator {
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final Pattern UPPERCASE = Pattern.compile("[A-Z]");
    private static final Pattern LOWERCASE = Pattern.compile("[a-z]");
    private static final Pattern NUMBER = Pattern.compile("\\d");
    private static final Pattern SPECIAL_CHAR = Pattern.compile("[^A-Za-z0-9]");

    public boolean isPasswordStrongEnough(String password) {
        if (password == null || password.isEmpty()) {
            return false;
        }

        if (password.length() < MIN_PASSWORD_LENGTH) {
            return false;
        }

        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return false;
        }

        if (!UPPERCASE.matcher(password).find()) {
            return false;
        }

        if (!LOWERCASE.matcher(password).find()) {
            return false;
        }

        if (!NUMBER.matcher(password).find()) {
            return false;
        }

        if (!SPECIAL_CHAR.matcher(password).find()) {
            return false;
        }

        return true;
    }
}
