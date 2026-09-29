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
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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

        assertThrows(AdminNotFoundException.class,
                () -> adminSignupService.login(dto, request));

        verify(adminEntityRepository).findByAdminName(dto.getAdminName());
        verify(logService).getClientIp(request);
        verify(encryptService, never()).getEncryptedPassword(anyString());
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
        when(encryptService.checkIfPasswordMatches(dto.getAdminPassword(), entity.getAdminPassword()))
                .thenReturn(false);

        assertThrows(WrongPasswordException.class,
                () -> adminSignupService.login(dto, request));

        verify(encryptService).checkIfPasswordMatches(dto.getAdminPassword(), entity.getAdminPassword());
        verify(logService).getClientIp(request);
        verify(encryptService, never()).getEncryptedPassword(anyString());
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
        when(tokenService.generateAdminToken(entity.getAdminName())).thenReturn("mockToken");

        String token = adminSignupService.login(dto, request);

        assertEquals("mockToken", token);
        verify(tokenService).generateAdminToken(entity.getAdminName());
    }

    @Test
    void failedLoginLog_recordsAccountAndReason_withoutAnyPasswordDerivedValue() {
        AdminSignDto dto = new AdminSignDto();
        dto.setAdminName("nonexistentUser");
        dto.setAdminPassword("SuperSecret123!");

        when(adminEntityRepository.findByAdminName(dto.getAdminName())).thenReturn(null);
        when(logService.getClientIp(request)).thenReturn("203.0.113.7");

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger loginLogger = (Logger) LoggerFactory.getLogger("com.migros.login");
        Level previousLevel = loginLogger.getLevel();
        loginLogger.setLevel(Level.WARN);
        loginLogger.addAppender(appender);
        try {
            assertThrows(AdminNotFoundException.class, () -> adminSignupService.login(dto, request));
        } finally {
            loginLogger.detachAppender(appender);
            loginLogger.setLevel(previousLevel);
            appender.stop();
        }

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        String formatted = String.valueOf(event.getFormattedMessage());

        assertTrue(formatted.contains("nonexistentUser"), "log must name the account: " + formatted);
        assertTrue(formatted.contains("203.0.113.7"), "log must name the client address: " + formatted);
        assertTrue(formatted.contains("account_not_found"), "log must name the reason: " + formatted);
        assertFalse(formatted.contains("SuperSecret123!"), "log must never contain the password: " + formatted);
        assertNull(event.getThrowableProxy(), "the failure must be logged without a stack trace payload");
        verify(encryptService, never()).getEncryptedPassword(anyString());
    }
}
