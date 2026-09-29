package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.dto.support.SupportCustomerMessageCreatedEventDto;
import com.example.MigrosBackend.dto.support.SupportMessageDeletedEventDto;
import com.example.MigrosBackend.dto.support.SupportMessageEditedEventDto;
import com.example.MigrosBackend.entity.support.SupportOutboxEventType;
import com.example.MigrosBackend.entity.user.SupportMessageEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Records the support-service events that a chat mutation owes.
 *
 * <p>This is the producer half of the outbox. It runs inside the caller's
 * transaction and only writes a durable row: the support service is never
 * contacted from a request thread, so a slow or unavailable receiver can no
 * longer block the customer's chat or cause the notification to be dropped
 * after the message was already committed. {@link SupportOutboxDispatcher}
 * performs the actual delivery.
 *
 * <p>Each call mints one {@code eventId} and stores it both as the outbox row's
 * primary key and inside the payload. It is never regenerated, so every retry
 * of a failed delivery resends a byte-identical body and the receiver can
 * deduplicate.
 *
 * <p>When no support-service base URL is configured the integration is
 * disabled and nothing is enqueued, preserving the previous opt-in behaviour.
 */
@Service
public class SupportInternalEventService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SupportInternalEventService.class);

    private final SupportOutboxStore outboxStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean enabled;

    public SupportInternalEventService(
            SupportOutboxStore outboxStore,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${support.service.base-url:}") String supportServiceBaseUrl
    ) {
        this.outboxStore = outboxStore;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.enabled = supportServiceBaseUrl != null && !supportServiceBaseUrl.isBlank();
    }

    /**
     * Must be called from inside the transaction that creates the message, so
     * the message and the event owed for it commit or roll back together.
     */
    public void publishCustomerMessageCreated(SupportMessageEntity entity) {
        if (!enabled) {
            LOGGER.debug("Outbound support-service integration is disabled; skipping customer message event");
            return;
        }
        String eventId = newEventId();
        enqueue(SupportOutboxEventType.CUSTOMER_MESSAGE_CREATED, eventId, entity.getUserMail(),
                new SupportCustomerMessageCreatedEventDto(
                        eventId,
                        entity.getUserMail(),
                        entity.getUserMail(),
                        String.valueOf(entity.getId()),
                        entity.getMessage(),
                        entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toString()
                ));
    }

    public void publishSupportMessageEdited(String userMail, String messageId, String text) {
        if (!enabled) {
            LOGGER.debug("Outbound support-service integration is disabled; skipping support message edit event");
            return;
        }
        String eventId = newEventId();
        enqueue(SupportOutboxEventType.SUPPORT_MESSAGE_EDITED, eventId, userMail,
                new SupportMessageEditedEventDto(eventId, userMail, messageId, text));
    }

    public void publishSupportMessageDeleted(String userMail, String messageId) {
        if (!enabled) {
            LOGGER.debug("Outbound support-service integration is disabled; skipping support message delete event");
            return;
        }
        String eventId = newEventId();
        enqueue(SupportOutboxEventType.SUPPORT_MESSAGE_DELETED, eventId, userMail,
                new SupportMessageDeletedEventDto(eventId, userMail, messageId));
    }

    public boolean isEnabled() {
        return enabled;
    }

    private void enqueue(SupportOutboxEventType type, String eventId, String userMail, Object payload) {
        outboxStore.enqueue(eventId, type.name(), userMail, serialize(type, payload), LocalDateTime.now(clock));
    }

    private String serialize(SupportOutboxEventType type, Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            // Failing to record an owed event must abort the caller's
            // transaction; silently skipping would commit the chat change and
            // lose the notification, which is the exact failure the outbox
            // exists to prevent.
            throw new IllegalStateException(
                    "Failed to serialize the " + type + " support event for outbox delivery", ex);
        }
    }

    private String newEventId() {
        return UUID.randomUUID().toString();
    }
}
