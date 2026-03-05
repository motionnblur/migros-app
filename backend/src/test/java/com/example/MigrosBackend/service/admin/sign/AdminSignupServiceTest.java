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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminSignupServiceTest {

    @Mock
    private AdminEntityRepository adminEntityRepository;
    @Mock
    private EncryptService encryptService;
    @Mock
    private LogService logService;
    @Mock
    private TokenService tokenService;
    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private AdminSignupService adminSignupService;

    @Test
    void login_shouldThrowAdminNotFoundException_whenUserDoesNotExist() {
        AdminSignDto dto = new AdminSignDto();
        dto.setAdminName("nonexistentUser");
        dto.setAdminPassword("password");

        when(adminEntityRepository.findByAdminName(dto.getAdminName())).thenReturn(null);
        when(logService.getClientIp(request)).thenReturn("127.0.0.1");
        when(encryptService.getEncryptedPassword(dto.getAdminPassword())).thenReturn("hashedPassword");

        assertThrows(AdminNotFoundException.class,
                () -> adminSignupService.login(dto, request));

        verify(adminEntityRepository).findByAdminName(dto.getAdminName());
        verify(logService).getClientIp(request);
        verify(encryptService).getEncryptedPassword(dto.getAdminPassword());
    }

    @Test
    void login_shouldThrowWrongPasswordException_whenPasswordDoesNotMatch() {
        AdminSignDto dto = new AdminSignDto();
        dto.setAdminName("nonexistentUser");
        dto.setAdminPassword("password");

        AdminEntity entity = new AdminEntity();
        entity.setAdminName("adminUser");
        entity.setAdminPassword("correctHashedPassword");

        when(adminEntityRepository.findByAdminName(dto.getAdminName())).thenReturn(entity);
        when(logService.getClientIp(request)).thenReturn("127.0.0.1");
        when(encryptService.getEncryptedPassword(dto.getAdminPassword())).thenReturn("wrongHashedPassword");
        when(encryptService.checkIfPasswordMatches(dto.getAdminPassword(), entity.getAdminPassword()))
                .thenReturn(false);

        assertThrows(WrongPasswordException.class,
                () -> adminSignupService.login(dto, request));

        verify(encryptService).checkIfPasswordMatches(dto.getAdminPassword(), entity.getAdminPassword());
    }

    @Test
    void login_shouldReturnToken_whenCredentialsAreValid() {
        AdminSignDto dto = new AdminSignDto();
        dto.setAdminName("nonexistentUser");
        dto.setAdminPassword("password");
        
        AdminEntity entity = new AdminEntity();
        entity.setAdminName("adminUser");
        entity.setAdminPassword("correctHashedPassword");

        when(adminEntityRepository.findByAdminName(dto.getAdminName())).thenReturn(entity);
        when(encryptService.checkIfPasswordMatches(dto.getAdminPassword(), entity.getAdminPassword()))
                .thenReturn(true);
        when(tokenService.generateToken(entity.getAdminName())).thenReturn("mockToken");

        String token = adminSignupService.login(dto, request);

        assertEquals("mockToken", token);
        verify(tokenService).generateToken(entity.getAdminName());
    }
}