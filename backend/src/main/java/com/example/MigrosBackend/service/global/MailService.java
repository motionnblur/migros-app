package com.example.MigrosBackend.service.global;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MailService {
    private final TemplateEngine templateEngine;
    private final JavaMailSender mailSender;
    private final RestTemplate restTemplate;
    private final String provider;
    private final String resendApiKey;
    private final String fromAddress;

    @Autowired
    public MailService(JavaMailSender mailSender,
                       RestTemplateBuilder restTemplateBuilder,
                       TemplateEngine templateEngine,
                       @Value("${app.mail.provider:auto}") String provider,
                       @Value("${resend.api.key:}") String resendApiKey,
                       @Value("${app.mail.from:}") String configuredFromAddress,
                       @Value("${spring.mail.username:}") String smtpUsername) {
        this.mailSender = mailSender;
        this.restTemplate = restTemplateBuilder.rootUri("https://api.resend.com").build();
        this.templateEngine = templateEngine;
        this.provider = provider == null ? "auto" : provider.trim().toLowerCase();
        this.resendApiKey = resendApiKey == null ? "" : resendApiKey.trim();
        String normalizedConfiguredAddress = configuredFromAddress == null ? "" : configuredFromAddress.trim();
        String normalizedSmtpUsername = smtpUsername == null ? "" : smtpUsername.trim();
        this.fromAddress = !normalizedConfiguredAddress.isEmpty()
                ? normalizedConfiguredAddress
                : normalizedSmtpUsername;
    }

    public void sendMimeMessage(String to, String subject, String templateName, Context context) throws MessagingException {
        String htmlContent = templateEngine.process(templateName, context);
        String resolvedProvider = resolveProvider();

        if ("smtp".equals(resolvedProvider)) {
            sendViaSmtp(to, subject, htmlContent);
            return;
        }

        sendViaResend(to, subject, htmlContent);
    }

    private String resolveProvider() {
        if ("smtp".equals(provider) || "resend".equals(provider)) {
            return provider;
        }

        return resendApiKey.isEmpty() ? "smtp" : "resend";
    }

    private void sendViaSmtp(String to, String subject, String htmlContent) throws MessagingException {
        if (fromAddress.isEmpty()) {
            throw new MessagingException("SMTP sender address is not configured. Set APP_MAIL_FROM or MAIL_USERNAME.");
        }

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
        helper.setFrom(fromAddress);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlContent, true);
        mailSender.send(message);
    }

    private void sendViaResend(String to, String subject, String htmlContent) throws MessagingException {
        if (resendApiKey.isEmpty()) {
            throw new MessagingException("RESEND_API_KEY is not configured.");
        }

        String resendFromAddress = fromAddress.isEmpty() ? "onboarding@resend.dev" : fromAddress;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(resendApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", resendFromAddress);
        payload.put("to", List.of(to));
        payload.put("subject", subject);
        payload.put("html", htmlContent);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/emails",
                    new HttpEntity<>(payload, headers),
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new MessagingException("Resend request failed with status code: " + response.getStatusCode().value());
            }
        } catch (RestClientException ex) {
            throw new MessagingException("Failed to send email via Resend API.", ex);
        }
    }
}
