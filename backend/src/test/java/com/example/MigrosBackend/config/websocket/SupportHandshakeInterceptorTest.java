package com.example.MigrosBackend.config.websocket;

import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.exception.shared.InvalidTokenException;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.websocket.SupportChatWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupportHandshakeInterceptorTest {

    private final TokenService tokenService = mock(TokenService.class);
    private final AdminEntityRepository adminEntityRepository = mock(AdminEntityRepository.class);
    private final SupportHandshakeInterceptor interceptor =
            new SupportHandshakeInterceptor(tokenService, adminEntityRepository);
    private final WebSocketHandler wsHandler = mock(WebSocketHandler.class);

    @Test
    void handshakeWithoutAnyCookieIsRejected() {
        HandshakeResult result = handshake();

        assertThat(result.accepted).isFalse();
        assertThat(result.servletResponse.getStatus()).isEqualTo(401);
    }

    @Test
    void handshakeWithInvalidUserCookieIsRejected() {
        when(tokenService.validateAndExtractUser("bad-token")).thenThrow(new InvalidTokenException());

        HandshakeResult result = handshake(cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "bad-token"));

        assertThat(result.accepted).isFalse();
        assertThat(result.servletResponse.getStatus()).isEqualTo(401);
    }

    @Test
    void handshakeWithValidUserCookieRegistersAuthenticatedUser() {
        when(tokenService.validateAndExtractUser("user-token")).thenReturn("user@example.com");

        HandshakeResult result = handshake(cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "user-token"));

        assertThat(result.accepted).isTrue();
        assertThat(result.attributes)
                .containsEntry(SupportChatWebSocketHandler.ATTR_USER_MAIL, "user@example.com")
                .containsEntry(SupportChatWebSocketHandler.ATTR_IS_ADMIN, false);
    }

    @Test
    void handshakeWithValidAdminCookieRegistersAdmin() {
        when(tokenService.validateAndExtractAdmin("admin-token")).thenReturn("manager@example.com");
        when(adminEntityRepository.findByAdminName("manager@example.com")).thenReturn(new AdminEntity());

        HandshakeResult result = handshake(cookie(AuthCookies.ADMIN_SESSION_COOKIE_NAME, "admin-token"));

        assertThat(result.accepted).isTrue();
        assertThat(result.attributes)
                .containsEntry(SupportChatWebSocketHandler.ATTR_USER_MAIL, "manager@example.com")
                .containsEntry(SupportChatWebSocketHandler.ATTR_IS_ADMIN, true);
    }

    @Test
    void handshakeWithAdminCookieForUnknownAdminIsRejected() {
        when(tokenService.validateAndExtractAdmin("ghost-token")).thenReturn("ghost@example.com");
        when(adminEntityRepository.findByAdminName("ghost@example.com")).thenReturn(null);

        HandshakeResult result = handshake(cookie(AuthCookies.ADMIN_SESSION_COOKIE_NAME, "ghost-token"));

        assertThat(result.accepted).isFalse();
        assertThat(result.servletResponse.getStatus()).isEqualTo(401);
    }

    @Test
    void userCookieTakesPrecedenceOverAdminCookie() {
        when(tokenService.validateAndExtractUser("user-token")).thenReturn("user@example.com");

        HandshakeResult result = handshake(
                cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "user-token"),
                cookie(AuthCookies.ADMIN_SESSION_COOKIE_NAME, "admin-token"));

        assertThat(result.accepted).isTrue();
        assertThat(result.attributes)
                .containsEntry(SupportChatWebSocketHandler.ATTR_USER_MAIL, "user@example.com")
                .containsEntry(SupportChatWebSocketHandler.ATTR_IS_ADMIN, false);
    }

    private HandshakeResult handshake(String... cookies) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws/support");
        if (cookies.length > 0) {
            servletRequest.addHeader(HttpHeaders.COOKIE, String.join("; ", cookies));
        }
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        ServletServerHttpRequest request = new ServletServerHttpRequest(servletRequest);
        ServletServerHttpResponse response = new ServletServerHttpResponse(servletResponse);
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        return new HandshakeResult(accepted, servletResponse, attributes);
    }

    private String cookie(String name, String value) {
        return name + "=" + value;
    }

    private record HandshakeResult(boolean accepted, MockHttpServletResponse servletResponse,
            Map<String, Object> attributes) {
    }
}
