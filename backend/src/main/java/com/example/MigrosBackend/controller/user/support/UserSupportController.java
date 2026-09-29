package com.example.MigrosBackend.controller.user.support;

import com.example.MigrosBackend.dto.user.support.SupportMessageDto;
import com.example.MigrosBackend.dto.user.support.SupportSendMessageDto;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.service.support.UserSupportChatService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/user/support")
public class UserSupportController {
    private final UserSupportChatService userSupportChatService;
    private final AuthTokenResolver authTokenResolver;

    @Autowired
    public UserSupportController(UserSupportChatService userSupportChatService, AuthTokenResolver authTokenResolver) {
        this.userSupportChatService = userSupportChatService;
        this.authTokenResolver = authTokenResolver;
    }

    @GetMapping("messages")
    public ResponseEntity<List<SupportMessageDto>> getSupportMessages() {
        return ResponseEntity.ok(userSupportChatService.getMessagesForUser(authTokenResolver.requireAuthenticatedUserMail()));
    }

    @PostMapping("send")
    public ResponseEntity<Void> sendSupportMessage(@Valid @RequestBody SupportSendMessageDto dto) {
        userSupportChatService.addUserMessage(authTokenResolver.requireAuthenticatedUserMail(), dto.getMessage());
        return ResponseEntity.ok().build();
    }
}
