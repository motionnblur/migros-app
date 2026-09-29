package com.example.MigrosBackend.service.admin.sign;

import com.example.MigrosBackend.dto.admin.sign.AdminSignDto;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.exception.admin.AdminNotFoundException;
import com.example.MigrosBackend.exception.shared.WrongPasswordException;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.global.EncryptService;
import com.example.MigrosBackend.service.global.LogService;
import com.example.MigrosBackend.service.global.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AdminSignupService {
    private static final Logger loginLogger = LoggerFactory.getLogger("com.migros.login");

    private final AdminEntityRepository adminEntityRepository;
    private final EncryptService encryptService;
    private final LogService logService;
    private final TokenService tokenService;

    @Autowired
    public AdminSignupService(AdminEntityRepository adminEntityRepository,
                              EncryptService encryptService,
                              LogService logService,
                              TokenService tokenService) {
        this.adminEntityRepository = adminEntityRepository;
        this.encryptService = encryptService;
        this.logService = logService;
        this.tokenService = tokenService;
    }

    public String login(AdminSignDto adminSignDto, HttpServletRequest request) {
        AdminEntity adminEntity = adminEntityRepository.findByAdminName(adminSignDto.getAdminName());
        if (adminEntity == null) {
            logFailedLogin(adminSignDto.getAdminName(), request, "account_not_found");
            throw new AdminNotFoundException(adminSignDto.getAdminName());
        }
        if (!encryptService.checkIfPasswordMatches(adminSignDto.getAdminPassword(), adminEntity.getAdminPassword())) {
            logFailedLogin(adminSignDto.getAdminName(), request, "password_mismatch");
            throw new WrongPasswordException();
        }

        return tokenService.generateAdminToken(adminEntity.getAdminName());
    }

    /**
     * Records a failed admin login for audit. Only the account name, the failure
     * reason, and the resolved client address are logged: neither the submitted
     * password nor a hash derived from it (including the BCrypt hash of a
     * non-existent account) is ever computed for logging, because a
     * password-derived value in a log file is a credential-equivalent secret.
     */
    private void logFailedLogin(String adminName, HttpServletRequest request, String reason) {
        loginLogger.warn("Failed login attempt | User: {} | IP: {} | Reason: {}",
                adminName, logService.getClientIp(request), reason);
    }
}
