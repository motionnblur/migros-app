package com.example.MigrosBackend.service.global;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MailServiceTest {
    @Mock
    private JavaMailSender emailSender;

    @Mock
    private TemplateEngine templateEngine;

    @Mock
    private MimeMessage mimeMessage;

    @InjectMocks
    private MailService mailService;

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

        // 2. Mock JavaMailSender to return our mocked MimeMessage
        when(emailSender.createMimeMessage()).thenReturn(mimeMessage);

        // Act
        mailService.sendMimeMessage(to, subject, templateName, context);

        // Assert
        // Verify Thymeleaf was called
        verify(templateEngine, times(1)).process(eq(templateName), any(Context.class));

        // Verify the message was actually sent
        verify(emailSender, times(1)).send(mimeMessage);
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

        // Verify emailSender.send was NEVER called because template failed first
        verify(emailSender, never()).send(any(MimeMessage.class));
    }
}