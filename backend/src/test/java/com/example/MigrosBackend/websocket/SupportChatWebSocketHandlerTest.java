package com.example.MigrosBackend.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupportChatWebSocketHandlerTest {

    @Test
    void afterConnectionEstablished_shouldAddSession() throws Exception {
        SupportChatWebSocketHandler handler = new SupportChatWebSocketHandler();
        WebSocketSession session = mock(WebSocketSession.class);

        handler.afterConnectionEstablished(session);

        Set<WebSocketSession> sessions = getSessions(handler);
        assertEquals(1, sessions.size());
        assertTrue(sessions.contains(session));
    }

    @Test
    void broadcastSupportUpdate_shouldSendMessage_whenSessionOpen() throws Exception {
        SupportChatWebSocketHandler handler = new SupportChatWebSocketHandler();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session);
        handler.broadcastSupportUpdate("user@mail.com");

        verify(session).sendMessage(org.mockito.ArgumentMatchers.argThat(msg -> {
            if (msg instanceof TextMessage text) {
                String payload = text.getPayload();
                return payload.contains("SUPPORT_UPDATED") && payload.contains("user@mail.com");
            }
            return false;
        }));
    }

    @Test
    void broadcastSupportUpdate_shouldRemoveSession_whenClosed() throws Exception {
        SupportChatWebSocketHandler handler = new SupportChatWebSocketHandler();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(false);

        handler.afterConnectionEstablished(session);
        handler.broadcastSupportUpdate("user@mail.com");

        Set<WebSocketSession> sessions = getSessions(handler);
        assertEquals(0, sessions.size());
    }

    @Test
    void broadcastSupportUpdate_shouldRemoveSession_whenSendFails() throws Exception {
        SupportChatWebSocketHandler handler = new SupportChatWebSocketHandler();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        doThrow(new RuntimeException("send failed")).when(session).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));

        handler.afterConnectionEstablished(session);
        handler.broadcastSupportUpdate("user@mail.com");

        Set<WebSocketSession> sessions = getSessions(handler);
        assertEquals(0, sessions.size());
    }

    @SuppressWarnings("unchecked")
    private Set<WebSocketSession> getSessions(SupportChatWebSocketHandler handler) throws Exception {
        Field field = SupportChatWebSocketHandler.class.getDeclaredField("sessions");
        field.setAccessible(true);
        return (Set<WebSocketSession>) field.get(handler);
    }

    @Test
    void afterConnectionClosed_shouldRemoveSession() throws Exception {
        SupportChatWebSocketHandler handler = new SupportChatWebSocketHandler();
        WebSocketSession session = mock(WebSocketSession.class);

        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        Set<WebSocketSession> sessions = getSessions(handler);
        assertEquals(0, sessions.size());
    }}








