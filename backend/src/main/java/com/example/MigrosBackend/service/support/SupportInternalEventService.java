package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.dto.support.SupportCustomerMessageCreatedEventDto;
import com.example.MigrosBackend.dto.support.SupportMessageDeletedEventDto;
import com.example.MigrosBackend.dto.support.SupportMessageEditedEventDto;
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
    private static final String SUPPORT_MESSAGE_EDITED_PATH = "/internal/events/support-message-edited";
    private static final String SUPPORT_MESSAGE_DELETED_PATH = "/internal/events/support-message-deleted";

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

        try {
            postEvent(CUSTOMER_MESSAGE_CREATED_PATH, payload);
        } catch (Exception ex) {
            // Non-blocking for user chat; in production, add retry/outbox.
            LOGGER.warn("Failed to publish customer support message event to support-service: {}", ex.getMessage());
        }
    }

    public void publishSupportMessageEdited(String userMail, String messageId, String text) {
        SupportMessageEditedEventDto payload = new SupportMessageEditedEventDto(
                UUID.randomUUID().toString(),
                userMail,
                messageId,
                text
        );

        postEvent(SUPPORT_MESSAGE_EDITED_PATH, payload);
    }

    public void publishSupportMessageDeleted(String userMail, String messageId) {
        SupportMessageDeletedEventDto payload = new SupportMessageDeletedEventDto(
                UUID.randomUUID().toString(),
                userMail,
                messageId
        );

        postEvent(SUPPORT_MESSAGE_DELETED_PATH, payload);
    }

    private void postEvent(String path, Object payload) {
        try {
            restTemplate.postForEntity(path, new HttpEntity<>(payload, buildHeaders()), Void.class);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to publish support internal event: " + ex.getMessage(), ex);
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (internalKey != null && !internalKey.isBlank()) {
            headers.set("x-internal-key", internalKey);
        }
        return headers;
    }
}
