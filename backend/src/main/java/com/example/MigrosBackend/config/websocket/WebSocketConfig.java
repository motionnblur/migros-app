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
    private final SupportHandshakeInterceptor supportHandshakeInterceptor;
    private final List<String> allowedOriginPatterns;

    @Autowired
    public WebSocketConfig(
            SupportChatWebSocketHandler supportChatWebSocketHandler,
            SupportHandshakeInterceptor supportHandshakeInterceptor,
            @Value("${app.allowed-origins:}") String allowedOriginsValue,
            @Value("${app.allowed-origin-patterns:}") String allowedOriginPatternsValue
    ) {
        this.supportChatWebSocketHandler = supportChatWebSocketHandler;
        this.supportHandshakeInterceptor = supportHandshakeInterceptor;
        List<String> parsedPatterns = parseCsvList(allowedOriginPatternsValue);
        if (parsedPatterns.isEmpty()) {
            parsedPatterns = parseCsvList(allowedOriginsValue);
        }
        this.allowedOriginPatterns = parsedPatterns;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Users connect under /ws/support (user_session cookie, path /). Admins
        // connect under /admin/ws/support because the admin_session cookie is
        // scoped to /admin and is therefore not sent to /ws/support.
        registry.addHandler(supportChatWebSocketHandler, "/ws/support", "/admin/ws/support")
                .addInterceptors(supportHandshakeInterceptor)
                .setAllowedOriginPatterns(allowedOriginPatterns.toArray(String[]::new));
    }

    private List<String> parseCsvList(String value) {
        return Arrays.stream((value == null ? "" : value).split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toList());
    }
}
