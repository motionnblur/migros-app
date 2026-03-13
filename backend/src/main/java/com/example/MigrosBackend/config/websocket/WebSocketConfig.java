package com.example.MigrosBackend.config.websocket;

import com.example.MigrosBackend.websocket.SupportChatWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final SupportChatWebSocketHandler supportChatWebSocketHandler;
    private final List<String> allowedOrigins;

    @Autowired
    public WebSocketConfig(
            SupportChatWebSocketHandler supportChatWebSocketHandler,
            @Value("${app.allowed-origins}") String allowedOriginsValue
    ) {
        this.supportChatWebSocketHandler = supportChatWebSocketHandler;
        this.allowedOrigins = Arrays.stream(allowedOriginsValue.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(supportChatWebSocketHandler, "/ws/support")
                .setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    }
}
