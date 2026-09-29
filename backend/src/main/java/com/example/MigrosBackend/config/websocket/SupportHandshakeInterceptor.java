package com.example.MigrosBackend.config.websocket;

import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.exception.shared.InvalidTokenException;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.websocket.SupportChatWebSocketHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;

/**
 * Authenticates the support WebSocket handshake with the same session cookies
 * the REST API uses. A valid {@code user_session} JWT registers the connection
 * as that user; a valid {@code admin_session} JWT (whose admin exists) registers
 * it as an admin. The client-supplied {@code userMail} query parameter is never
 * trusted. A handshake without a valid cookie is rejected with 401.
 */
@Component
public class SupportHandshakeInterceptor implements HandshakeInterceptor {

    private final TokenService tokenService;
    private final AdminEntityRepository adminEntityRepository;

    public SupportHandshakeInterceptor(TokenService tokenService, AdminEntityRepository adminEntityRepository) {
        this.tokenService = tokenService;
        this.adminEntityRepository = adminEntityRepository;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String userToken = readCookie(request, AuthCookies.USER_SESSION_COOKIE_NAME);
        if (userToken != null) {
            try {
                String userMail = tokenService.validateAndExtractUser(userToken);
                attributes.put(SupportChatWebSocketHandler.ATTR_USER_MAIL, userMail);
                attributes.put(SupportChatWebSocketHandler.ATTR_IS_ADMIN, false);
                return true;
            } catch (InvalidTokenException ignored) {
                // Fall through and try the admin cookie.
            }
        }

        String adminToken = readCookie(request, AuthCookies.ADMIN_SESSION_COOKIE_NAME);
        if (adminToken != null) {
            try {
                String adminName = tokenService.validateAndExtractAdmin(adminToken);
                if (adminEntityRepository.findByAdminName(adminName) != null) {
                    attributes.put(SupportChatWebSocketHandler.ATTR_USER_MAIL, adminName);
                    attributes.put(SupportChatWebSocketHandler.ATTR_IS_ADMIN, true);
                    return true;
                }
            } catch (InvalidTokenException ignored) {
                // Rejected below.
            }
        }

        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // Nothing to do after the upgrade.
    }

    private String readCookie(ServerHttpRequest request, String cookieName) {
        List<String> cookieHeaders = request.getHeaders().get(HttpHeaders.COOKIE);
        if (cookieHeaders == null) {
            return null;
        }

        for (String cookieHeader : cookieHeaders) {
            if (cookieHeader == null) {
                continue;
            }
            for (String pair : cookieHeader.split(";")) {
                int separator = pair.indexOf('=');
                if (separator < 0) {
                    continue;
                }
                String name = pair.substring(0, separator).trim();
                if (cookieName.equals(name)) {
                    return pair.substring(separator + 1).trim();
                }
            }
        }

        return null;
    }
}
