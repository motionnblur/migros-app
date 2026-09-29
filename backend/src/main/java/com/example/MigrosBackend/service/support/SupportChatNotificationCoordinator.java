package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.entity.user.SupportMessageEntity;
import com.example.MigrosBackend.websocket.SupportChatWebSocketHandler;
import org.springframework.stereotype.Component;

@Component
final class SupportChatNotificationCoordinator {
    private final SupportChatWebSocketHandler supportChatWebSocketHandler;
    private final SupportInternalEventService supportInternalEventService;

    SupportChatNotificationCoordinator(
            SupportChatWebSocketHandler supportChatWebSocketHandler,
            SupportInternalEventService supportInternalEventService
    ) {
        this.supportChatWebSocketHandler = supportChatWebSocketHandler;
        this.supportInternalEventService = supportInternalEventService;
    }

    void publishCustomerMessageCreated(SupportMessageEntity entity, String userMail) {
        supportInternalEventService.publishCustomerMessageCreated(entity);
        supportChatWebSocketHandler.broadcastSupportUpdate(userMail);
    }

    void broadcastSupportMessageCreated(String userMail, String sender, Long messageId) {
        supportChatWebSocketHandler.broadcastSupportMessageCreated(userMail, sender, messageId);
    }

    void broadcastSupportUpdate(String userMail) {
        supportChatWebSocketHandler.broadcastSupportUpdate(userMail);
    }

    void publishSupportMessageEdited(String userMail, String messageId, String text) {
        supportInternalEventService.publishSupportMessageEdited(userMail, messageId, text);
        supportChatWebSocketHandler.broadcastSupportUpdate(userMail);
    }

    void publishSupportMessageDeleted(String userMail, String messageId) {
        supportInternalEventService.publishSupportMessageDeleted(userMail, messageId);
        supportChatWebSocketHandler.broadcastSupportUpdate(userMail);
    }
}
