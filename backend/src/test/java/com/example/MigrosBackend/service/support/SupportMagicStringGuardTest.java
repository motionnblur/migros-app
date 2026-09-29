package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.entity.support.SupportOutboxStatus;
import com.example.MigrosBackend.entity.user.SupportMessageSender;
import com.example.MigrosBackend.websocket.SupportChatWebSocketHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the enum-backed constants that stand in for SQL and wire literals.
 *
 * <p>A rename of an enum constant used to be invisible: the SQL kept matching
 * the old string and the claim scan silently returned nothing. These assertions
 * make the enum the single source of truth, so a rename either compiles (and
 * this test proves the tokens followed) or fails here.
 */
class SupportMagicStringGuardTest {

    @Test
    void outboxStatusTokensMatchTheEnumNamesUsedInSql() {
        assertEquals(SupportOutboxStatus.PENDING.name(), SupportOutboxStore.STATUS_PENDING);
        assertEquals(SupportOutboxStatus.PROCESSING.name(), SupportOutboxStore.STATUS_PROCESSING);
        assertEquals(SupportOutboxStatus.DELIVERED.name(), SupportOutboxStore.STATUS_DELIVERED);
    }

    @Test
    void senderTokensMatchTheStoredWireValues() {
        assertEquals("USER", SupportMessageSender.USER.name());
        assertEquals("MANAGEMENT", SupportMessageSender.MANAGEMENT.name());
    }

    @Test
    void realtimeEventTypeTokensMatchTheClientContract() {
        assertEquals("SUPPORT_UPDATED", SupportChatWebSocketHandler.EVENT_TYPE_SUPPORT_UPDATED);
        assertEquals("SUPPORT_MESSAGE_CREATED", SupportChatWebSocketHandler.EVENT_TYPE_SUPPORT_MESSAGE_CREATED);
    }
}
