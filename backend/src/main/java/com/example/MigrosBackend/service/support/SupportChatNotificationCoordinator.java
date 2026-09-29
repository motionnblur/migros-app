package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.entity.user.SupportMessageEntity;
import com.example.MigrosBackend.websocket.SupportChatWebSocketHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owns the outbox enqueue and the in-memory WebSocket notification for every
 * chat mutation.
 *
 * <p>The durable outbox row is written inline, inside the caller's transaction,
 * so it commits or rolls back with the chat write. The WebSocket broadcast is a
 * purely in-memory side effect and is deferred until the transaction commits,
 * so a client polling on the notification never reads stale data and a rollback
 * never announces a change that did not happen. When no transaction
 * synchronization is active (a direct call outside a transaction) the broadcast
 * runs inline, as before.
 */
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
        broadcastAfterCommit(() -> supportChatWebSocketHandler.broadcastSupportUpdate(userMail));
    }

    void broadcastSupportMessageCreated(String userMail, String sender, Long messageId) {
        broadcastAfterCommit(() -> supportChatWebSocketHandler.broadcastSupportMessageCreated(userMail, sender, messageId));
    }

    void broadcastSupportUpdate(String userMail) {
        broadcastAfterCommit(() -> supportChatWebSocketHandler.broadcastSupportUpdate(userMail));
    }

    void publishSupportMessageEdited(String userMail, String messageId, String text) {
        supportInternalEventService.publishSupportMessageEdited(userMail, messageId, text);
        broadcastAfterCommit(() -> supportChatWebSocketHandler.broadcastSupportUpdate(userMail));
    }

    void publishSupportMessageDeleted(String userMail, String messageId) {
        supportInternalEventService.publishSupportMessageDeleted(userMail, messageId);
        broadcastAfterCommit(() -> supportChatWebSocketHandler.broadcastSupportUpdate(userMail));
    }

    private void broadcastAfterCommit(Runnable broadcast) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            broadcast.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                broadcast.run();
            }
        });
    }
}
