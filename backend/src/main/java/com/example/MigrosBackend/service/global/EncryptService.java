package com.example.MigrosBackend.service.global;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class EncryptService {
    private final PasswordEncoder passwordEncoder;

    public EncryptService(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    public String getEncryptedPassword(String decryptedPassword) {
        return passwordEncoder.encode(decryptedPassword);
    }
    public boolean checkIfPasswordMatches(String decryptedPassword, String encryptedPassword) {
        return passwordEncoder.matches(decryptedPassword, encryptedPassword);
    }
}
