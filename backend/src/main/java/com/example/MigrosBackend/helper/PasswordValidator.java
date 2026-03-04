package com.example.MigrosBackend.helper;

import org.springframework.stereotype.Component;

@Component
public class PasswordValidator {
    public boolean isPasswordStrongEnough(String password) {
        int minPasswordLength = 8;
        boolean requiresUppercase = true;
        boolean requiresLowercase = true;
        boolean requiresNumber = true;
        boolean requiresSpecialChar = true;

        if (password.length() < minPasswordLength) {
            return false;
        }

        if (requiresUppercase && !password.matches(".*[A-Z].*")) {
            return false;
        }

        if (requiresLowercase && !password.matches(".*[a-z].*")) {
            return false;
        }

        if (requiresNumber && !password.matches(".*\\d.*")) {
            return false;
        }

        if (requiresSpecialChar && !password.matches(".*[^A-Za-z0-9].*")) {
            return false;
        }

        return true;
    }
}
