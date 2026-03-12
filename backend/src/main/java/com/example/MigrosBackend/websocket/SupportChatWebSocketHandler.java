package com.example.MigrosBackend.websocket;

import com.example.MigrosBackend.dto.support.SupportRealtimeEventDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SupportChatWebSocketHandler extends TextWebSocketHandler {
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<String>> userMailToSessionIds = new ConcurrentHashMap<>();
    private final Map<String, String> sessionIdToUserMail = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        registerCustomerSession(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        unregisterCustomerSession(session.getId());
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
        sendToAll(new SupportRealtimeEventDto("SUPPORT_UPDATED", userMail, null, null));
    }

    public void broadcastSupportMessageCreated(String userMail, String sender, Long messageId) {
        sendToAll(new SupportRealtimeEventDto("SUPPORT_MESSAGE_CREATED", userMail, sender, messageId));
    }

    private void sendToAll(SupportRealtimeEventDto event) {
        String payload;

        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception ex) {
            return;
        }

        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                unregisterCustomerSession(session.getId());
                continue;
            }

            try {
                session.sendMessage(new TextMessage(payload));
            } catch (Exception ex) {
                sessions.remove(session);
                unregisterCustomerSession(session.getId());
            }
        }
    }

    private void registerCustomerSession(WebSocketSession session) {
        String userMail = extractQueryParam(session.getUri(), "userMail");
        if (userMail == null || userMail.isBlank()) {
            return;
        }

        String normalizedUserMail = normalize(userMail);
        if (normalizedUserMail.isEmpty()) {
            return;
        }

        sessionIdToUserMail.put(session.getId(), normalizedUserMail);
        userMailToSessionIds.computeIfAbsent(normalizedUserMail, key -> ConcurrentHashMap.newKeySet()).add(session.getId());
    }

    private void unregisterCustomerSession(String sessionId) {
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
            userMailToSessionIds.remove(userMail);
        }
    }

    private String extractQueryParam(URI uri, String key) {
        if (uri == null || uri.getQuery() == null || key == null || key.isBlank()) {
            return null;
        }

        String[] params = uri.getQuery().split("&");
        for (String param : params) {
            String[] pair = param.split("=", 2);
            if (pair.length != 2 || !key.equals(pair[0])) {
                continue;
            }

            return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        }

        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}


