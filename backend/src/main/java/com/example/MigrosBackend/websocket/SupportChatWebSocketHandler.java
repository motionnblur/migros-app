package com.example.MigrosBackend.websocket;

import com.example.MigrosBackend.dto.support.SupportRealtimeEventDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Realtime fan-out for the support chat.
 *
 * <p>Sessions are only registered after the handshake interceptor has validated
 * a session cookie, so identity never comes from the client. A support event
 * about a customer is delivered to that customer's own sessions plus the admin
 * sessions; no other customer's identifier is ever sent to a session that does
 * not own it. Registered sessions are wrapped in a
 * {@link ConcurrentWebSocketSessionDecorator} so concurrent sends from request
 * threads cannot interleave frames.</p>
 */
@Component
public class SupportChatWebSocketHandler extends TextWebSocketHandler {
    public static final String ATTR_USER_MAIL = "userMail";
    public static final String ATTR_IS_ADMIN = "isAdmin";

    private static final Logger log = LoggerFactory.getLogger(SupportChatWebSocketHandler.class);
    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;
    private static final int SEND_BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private final Map<String, WebSocketSession> sessionsById = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userMailToSessionIds = new ConcurrentHashMap<>();
    private final Map<String, String> sessionIdToUserMail = new ConcurrentHashMap<>();
    private final Set<String> adminSessionIds = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (session == null) {
            return;
        }

        WebSocketSession registeredSession = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_SIZE_LIMIT_BYTES);
        sessionsById.put(session.getId(), registeredSession);
        registerAuthenticatedSession(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (session == null) {
            return;
        }
        unregisterSession(session.getId());
    }

    public boolean isUserOnline(String userMail) {
        String normalizedUserMail = normalize(userMail);
        if (normalizedUserMail.isEmpty()) {
            return false;
        }

        Set<String> sessionIds = userMailToSessionIds.get(normalizedUserMail);
        return sessionIds != null && !sessionIds.isEmpty();
    }

    public void broadcastSupportUpdate(String userMail) {
        sendToTargets(new SupportRealtimeEventDto("SUPPORT_UPDATED", userMail, null, null), userMail);
    }

    public void broadcastSupportMessageCreated(String userMail, String sender, Long messageId) {
        sendToTargets(new SupportRealtimeEventDto("SUPPORT_MESSAGE_CREATED", userMail, sender, messageId), userMail);
    }

    private void sendToTargets(SupportRealtimeEventDto event, String userMail) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception ex) {
            // The payload can contain customer identifiers; never log it or the
            // message text, only the event type.
            log.warn("Failed to serialize support realtime event type={}", event.getType());
            return;
        }

        Set<String> targetSessionIds = new HashSet<>();
        Set<String> userSessionIds = userMailToSessionIds.get(normalize(userMail));
        if (userSessionIds != null) {
            targetSessionIds.addAll(userSessionIds);
        }
        targetSessionIds.addAll(adminSessionIds);

        for (String sessionId : targetSessionIds) {
            WebSocketSession session = sessionsById.get(sessionId);
            if (session != null) {
                send(session, payload);
            }
        }
    }

    private void send(WebSocketSession session, String payload) {
        if (!session.isOpen()) {
            unregisterSession(session.getId());
            return;
        }

        try {
            session.sendMessage(new TextMessage(payload));
        } catch (Exception ex) {
            unregisterSession(session.getId());
        }
    }

    private void registerAuthenticatedSession(WebSocketSession session) {
        Map<String, Object> attributes = session.getAttributes();
        if (attributes == null) {
            return;
        }

        String sessionId = session.getId();
        if (Boolean.TRUE.equals(attributes.get(ATTR_IS_ADMIN))) {
            adminSessionIds.add(sessionId);
            return;
        }

        String userMail = normalize((String) attributes.get(ATTR_USER_MAIL));
        if (userMail.isEmpty()) {
            return;
        }

        sessionIdToUserMail.put(sessionId, userMail);
        userMailToSessionIds.computeIfAbsent(userMail, key -> ConcurrentHashMap.newKeySet()).add(sessionId);
    }

    private void unregisterSession(String sessionId) {
        if (sessionId == null) {
            return;
        }

        sessionsById.remove(sessionId);
        adminSessionIds.remove(sessionId);

        String userMail = sessionIdToUserMail.remove(sessionId);
        if (userMail == null) {
            return;
        }

        Set<String> sessionIds = userMailToSessionIds.get(userMail);
        if (sessionIds == null) {
            return;
        }

        sessionIds.remove(sessionId);
        if (sessionIds.isEmpty()) {
            // Conditional remove so a concurrent re-register for the same mailbox
            // cannot have its freshly added entry dropped.
            userMailToSessionIds.remove(userMail, sessionIds);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
