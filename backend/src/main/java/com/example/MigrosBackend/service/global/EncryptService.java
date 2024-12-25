package com.example.MigrosBackend.service.global;

import com.example.MigrosBackend.config.SecurityConfiguration;
import org.springframework.stereotype.Service;

@Service
public class EncryptService {
    private final SecurityConfiguration securityConfiguration;

    public EncryptService(SecurityConfiguration securityConfiguration) {
        this.securityConfiguration = securityConfiguration;
    }

    public String getEncryptedPassword(String decryptedPassword) {
        return securityConfiguration.passwordEncoder().encode(decryptedPassword);
    }
    public boolean checkIfPasswordMatches(String decryptedPassword, String encryptedPassword) {
        return securityConfiguration.passwordEncoder().matches(decryptedPassword, encryptedPassword);
    }
}
