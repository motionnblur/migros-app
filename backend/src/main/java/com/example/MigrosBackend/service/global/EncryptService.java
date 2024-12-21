package com.example.MigrosBackend.service.global;

import com.example.MigrosBackend.config.SecurityConfiguration;
import org.springframework.stereotype.Service;

@Service
public class EncryptService {
    private final SecurityConfiguration securityConfiguration;

    public EncryptService(SecurityConfiguration securityConfiguration) {
        this.securityConfiguration = securityConfiguration;
    }

    public String getEncryptedPassword(String password) {
        return securityConfiguration.passwordEncoder().encode(password);
    }
    public boolean checkIfPasswordMatches(String password, String encryptedPassword) {
        return securityConfiguration.passwordEncoder().matches(password, encryptedPassword);
    }
}
