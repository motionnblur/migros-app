package com.example.MigrosBackend.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupportChatWebSocketHandlerTest {

    @Test
    void authenticatedUserSessionIsRegisteredForPresence() {
        SupportChatWebSocketHandler handler = new SupportChatWebSocketHandler();
        WebSocketSession session = userSession("session-a", "user@mail.com");

        handler.afterConnectionEstablished(session);

        assertTrue(handler.isUserOnline("user@mail.com"));
        assertTrue(handler.isUserOnline("USER@MAIL.COM"), "presence is case-insensitive");
    }

    @Test
    void sessionWithoutIdentityIsNotRegisteredForPresence() {
        SupportChatWebSocketHandler handler = new SupportChatWebSocketHandler();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("anonymous");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new HashMap<>());

        handler.afterConnectionEstablished(session);

        assertFalse(handler.isUserOnline("user@mail.com"));
    }

    @Test
    void userDoesNotReceiveAnotherCustomersEvent() throws Exception {
        SupportChatWebSocketHandler handler = new SupportChatWebSocketHandler();
        WebSocketSession userA = userSession("session-a", "a@mail.com");
        WebSocketSession userB = userSession("session-b", "b@mail.com");

        handler.afterConnectionEstablished(userA);
        handler.afterConnectionEstablished(userB);
        handler.broadcastSupportUpdate("b@mail.com");

        verify(userB).sendMessage(any(TextMessage.class));
        verify(userA, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void userReceivesOwnEvent() throws Exception {
        SupportChatWebSocketHandler handler = new SupportChatWebSocketHandler();
        WebSocketSession userA = userSession("session-a", "a@mail.com");
        handler.afterConnectionEstablished(userA);

        handler.broadcastSupportMessageCreated("a@mail.com", "MANAGEMENT", 42L);

        verify(userA).sendMessage(any(TextMessage.class));
    }

    @Test
    void adminReceivesEventsAboutAnyCustomer() throws Exception {
        SupportChatWebSocketHandler handler = new SupportChatWebSocketHandler();
        WebSocketSession admin = adminSession("admin-session");
        WebSocketSession userA = userSession("session-a", "a@mail.com");

        handler.afterConnectionEstablished(admin);
        handler.afterConnectionEstablished(userA);

        handler.broadcastSupportUpdate("b@mail.com");
        handler.broadcastSupportMessageCreated("a@mail.com", "MANAGEMENT", 7L);

        verify(admin, times(2)).sendMessage(any(TextMessage.class));
    }

    @Test
    void closedSessionIsRemovedFromPresenceAndTargets() throws Exception {
        SupportChatWebSocketHandler handler = new SupportChatWebSocketHandler();
        WebSocketSession userA = userSession("session-a", "a@mail.com");
        handler.afterConnectionEstablished(userA);

        handler.afterConnectionClosed(userA, CloseStatus.NORMAL);
        handler.broadcastSupportUpdate("a@mail.com");

        assertFalse(handler.isUserOnline("a@mail.com"));
        verify(userA, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void failedSendUnregistersTheSession() throws Exception {
        SupportChatWebSocketHandler handler = new SupportChatWebSocketHandler();
        WebSocketSession userA = userSession("session-a", "a@mail.com");
        doThrow(new RuntimeException("send failed")).when(userA).sendMessage(any(TextMessage.class));

        handler.afterConnectionEstablished(userA);
        handler.broadcastSupportUpdate("a@mail.com");

        assertFalse(handler.isUserOnline("a@mail.com"));
    }

    private WebSocketSession userSession(String sessionId, String userMail) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(SupportChatWebSocketHandler.ATTR_USER_MAIL, userMail);
        attributes.put(SupportChatWebSocketHandler.ATTR_IS_ADMIN, false);
        return session(sessionId, attributes);
    }

    private WebSocketSession adminSession(String sessionId) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(SupportChatWebSocketHandler.ATTR_IS_ADMIN, true);
        return session(sessionId, attributes);
    }

    private WebSocketSession session(String sessionId, Map<String, Object> attributes) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }
}
