package com.example.MigrosBackend.service.admin.sign;

import com.example.MigrosBackend.dto.admin.sign.AdminSignDto;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.global.EncryptService;
import com.example.MigrosBackend.service.global.LogService;
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

    @Autowired
    public AdminSignupService(AdminEntityRepository adminEntityRepository,
                              EncryptService encryptService,
                              LogService logService) {
        this.adminEntityRepository = adminEntityRepository;
        this.encryptService = encryptService;
        this.logService = logService;
    }
    public void login(AdminSignDto adminSignDto, HttpServletRequest request) {
        AdminEntity adminEntity = adminEntityRepository.findByAdminName(adminSignDto.getAdminName());
        if (adminEntity == null)
        {
            String userIpAddress = logService.getClientIp(request);
            String attemptedPassword = encryptService.getEncryptedPassword(adminSignDto.getAdminPassword());

            loginLogger.warn("Failed login attempt - User: {} | Password Hash: {} | IP: {} | Reason: User not found",
                    adminSignDto.getAdminName(), attemptedPassword, userIpAddress);
            throw new RuntimeException("Admin with that name: " + adminSignDto.getAdminName() + " could not be found.");
        }
        if(!encryptService.checkIfPasswordMatches(adminSignDto.getAdminPassword(), adminEntity.getAdminPassword()))
        {
            String userIpAddress = logService.getClientIp(request);
            String attemptedPassword = encryptService.getEncryptedPassword(adminSignDto.getAdminPassword());

            loginLogger.warn("Failed login attempt - User: {} | Password Hash: {} | IP: {} | Reason: Wrong password",
                    adminSignDto.getAdminName(), attemptedPassword, userIpAddress);

            throw new RuntimeException("Wrong password.");
        }
    }
}
