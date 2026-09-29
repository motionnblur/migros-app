package com.example.MigrosBackend.service.global;

import com.example.MigrosBackend.exception.user.MailSendingFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
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
                       @Value("${spring.mail.username:}") String smtpUsername,
                       @Value("${app.mail.http-connect-timeout-ms:5000}") long connectTimeoutMs,
                       @Value("${app.mail.http-read-timeout-ms:10000}") long readTimeoutMs) {
        this.mailSender = mailSender;
        this.restTemplate = restTemplateBuilder
                .rootUri("https://api.resend.com")
                .setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .setReadTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
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
        if (fromAddress.isEmpty()) {
            throw new MessagingException(
                    "Mail sender address is not configured. Set APP_MAIL_FROM or MAIL_USERNAME.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(resendApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", fromAddress);
        payload.put("to", List.of(to));
        payload.put("subject", subject);
        payload.put("html", htmlContent);

        try {
            // A 4xx/5xx response, a connection failure, and a connect/read
            // timeout all surface as a RestClientException here and share the
            // single failure path below, so the signup flow revokes the token
            // it already committed for this send.
            restTemplate.postForEntity(
                    "/emails",
                    new HttpEntity<>(payload, headers),
                    String.class
            );
        } catch (RestClientException ex) {
            throw new MailSendingFailedException();
        }
    }
}
