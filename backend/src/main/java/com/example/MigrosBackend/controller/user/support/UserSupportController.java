package com.example.MigrosBackend.controller.user.support;

import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.dto.user.support.SupportMessageDto;
import com.example.MigrosBackend.dto.user.support.SupportSendMessageDto;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.service.support.SupportChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("user/support")
public class UserSupportController {
    private final SupportChatService supportChatService;
    private final AuthTokenResolver authTokenResolver;

    @Autowired
    public UserSupportController(SupportChatService supportChatService, AuthTokenResolver authTokenResolver) {
        this.supportChatService = supportChatService;
        this.authTokenResolver = authTokenResolver;
    }

    @GetMapping("messages")
    public ResponseEntity<List<SupportMessageDto>> getSupportMessages(
            @CookieValue(name = AuthCookies.USER_SESSION_COOKIE_NAME, required = false) String token) {
        return ResponseEntity.ok(supportChatService.getMessagesForUser(authTokenResolver.requireToken(token)));
    }

    @PostMapping("send")
    public ResponseEntity<Void> sendSupportMessage(
            @CookieValue(name = AuthCookies.USER_SESSION_COOKIE_NAME, required = false) String token,
            @RequestBody SupportSendMessageDto dto) {
        supportChatService.addUserMessage(authTokenResolver.requireToken(token), dto.getMessage());
        return ResponseEntity.ok().build();
    }
}
