package com.example.MigrosBackend.service.global;

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MailServiceTest {
    @Mock
    private RestTemplateBuilder restTemplateBuilder;

    @Mock
    private TemplateEngine templateEngine;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private JavaMailSender javaMailSender;

    private MailService mailService;

    @BeforeEach
    void setUp() {
        when(restTemplateBuilder.rootUri("https://api.resend.com")).thenReturn(restTemplateBuilder);
        when(restTemplateBuilder.build()).thenReturn(restTemplate);
        mailService = new MailService(
                javaMailSender,
                restTemplateBuilder,
                templateEngine,
                "resend",
                "test_resend_key",
                "onboarding@resend.dev",
                "smtp@example.com"
        );
    }

    @Test
    void sendMimeMessage_Success() throws MessagingException {
        // Arrange
        String to = "user@example.com";
        String subject = "Welcome!";
        String templateName = "welcome-email";
        Context context = new Context();
        String processedHtml = "<html><body>Welcome!</body></html>";

        // 1. Mock Thymeleaf processing
        when(templateEngine.process(eq(templateName), any(Context.class))).thenReturn(processedHtml);

        // 2. Mock Resend API success response
        when(restTemplate.postForEntity(eq("/emails"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        // Act
        mailService.sendMimeMessage(to, subject, templateName, context);

        // Assert
        // Verify Thymeleaf was called
        verify(templateEngine, times(1)).process(eq(templateName), any(Context.class));

        // Verify Resend API call was made
        verify(restTemplate, times(1)).postForEntity(eq("/emails"), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void sendMimeMessage_ShouldThrowException_WhenTemplateProcessingFails() {
        // Arrange
        Context context = new Context();
        when(templateEngine.process(anyString(), any(Context.class)))
                .thenThrow(new RuntimeException("Template not found"));

        // Act & Assert
        assertThrows(RuntimeException.class, () ->
                mailService.sendMimeMessage("test@test.com", "Sub", "wrong-temp", context)
        );

        // Verify Resend API was NEVER called because template failed first
        verify(restTemplate, never()).postForEntity(anyString(), any(HttpEntity.class), eq(String.class));
    }
}
