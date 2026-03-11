package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.dto.support.SupportCustomerMessageCreatedEventDto;
import com.example.MigrosBackend.entity.user.SupportMessageEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.ZoneId;
import java.util.UUID;

@Service
public class SupportInternalEventService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SupportInternalEventService.class);
    private static final String CUSTOMER_MESSAGE_CREATED_PATH = "/internal/events/customer-message-created";

    private final RestTemplate restTemplate;
    private final String internalKey;

    public SupportInternalEventService(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${support.service.base-url:http://localhost:3000}") String supportServiceBaseUrl,
            @Value("${support.service.internal-key:}") String internalKey
    ) {
        this.restTemplate = restTemplateBuilder.rootUri(supportServiceBaseUrl).build();
        this.internalKey = internalKey;
    }

    public void publishCustomerMessageCreated(SupportMessageEntity entity) {
        SupportCustomerMessageCreatedEventDto payload = new SupportCustomerMessageCreatedEventDto(
                UUID.randomUUID().toString(),
                entity.getUserMail(),
                entity.getUserMail(),
                String.valueOf(entity.getId()),
                entity.getMessage(),
                entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toString()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (internalKey != null && !internalKey.isBlank()) {
            headers.set("x-internal-key", internalKey);
        }

        try {
            restTemplate.postForEntity(CUSTOMER_MESSAGE_CREATED_PATH, new HttpEntity<>(payload, headers), Void.class);
        } catch (Exception ex) {
            // Non-blocking for user chat; in production, add retry/outbox.
            LOGGER.warn("Failed to publish customer support message event to support-service: {}", ex.getMessage());
        }
    }
}
