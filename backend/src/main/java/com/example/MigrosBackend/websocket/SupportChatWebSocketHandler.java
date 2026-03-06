package com.example.MigrosBackend.websocket;

import com.example.MigrosBackend.dto.support.SupportRealtimeEventDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SupportChatWebSocketHandler extends TextWebSocketHandler {
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    public void broadcastSupportUpdate(String userMail) {
        sendToAll(new SupportRealtimeEventDto("SUPPORT_UPDATED", userMail));
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
                continue;
            }

            try {
                session.sendMessage(new TextMessage(payload));
            } catch (Exception ex) {
                sessions.remove(session);
            }
        }
    }
}
