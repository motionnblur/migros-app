package com.example.MigrosBackend.config.websocket;

import com.example.MigrosBackend.websocket.SupportChatWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final SupportChatWebSocketHandler supportChatWebSocketHandler;

    @Autowired
    public WebSocketConfig(SupportChatWebSocketHandler supportChatWebSocketHandler) {
        this.supportChatWebSocketHandler = supportChatWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(supportChatWebSocketHandler, "/ws/support")
                .setAllowedOrigins("http://localhost:4200", "http://localhost:5000");
    }
}
