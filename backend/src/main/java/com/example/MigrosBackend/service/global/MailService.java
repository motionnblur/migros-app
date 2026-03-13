package com.example.MigrosBackend.service.global;

import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final RestTemplate restTemplate;
    private final String resendApiKey;
    private final String fromAddress;

    @Autowired
    public MailService(RestTemplateBuilder restTemplateBuilder,
                       TemplateEngine templateEngine,
                       @Value("${resend.api.key:}") String resendApiKey,
                       @Value("${app.mail.from:onboarding@resend.dev}") String configuredFromAddress) {
        this.restTemplate = restTemplateBuilder.rootUri("https://api.resend.com").build();
        this.templateEngine = templateEngine;
        this.resendApiKey = resendApiKey == null ? "" : resendApiKey.trim();
        this.fromAddress = configuredFromAddress == null ? "onboarding@resend.dev" : configuredFromAddress.trim();
    }

    public void sendMimeMessage(String to, String subject, String templateName, Context context) throws MessagingException {
        if (resendApiKey.isEmpty()) {
            throw new MessagingException("RESEND_API_KEY is not configured.");
        }

        String htmlContent = templateEngine.process(templateName, context);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(resendApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", fromAddress);
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
