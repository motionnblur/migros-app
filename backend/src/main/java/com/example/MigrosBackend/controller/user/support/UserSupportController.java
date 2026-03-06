package com.example.MigrosBackend.controller.user.support;

import com.example.MigrosBackend.dto.user.support.SupportMessageDto;
import com.example.MigrosBackend.dto.user.support.SupportSendMessageDto;
import com.example.MigrosBackend.service.support.SupportChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("user/support")
public class UserSupportController {
    private final SupportChatService supportChatService;

    @Autowired
    public UserSupportController(SupportChatService supportChatService) {
        this.supportChatService = supportChatService;
    }

    @GetMapping("messages")
    public ResponseEntity<List<SupportMessageDto>> getSupportMessages(@RequestParam String token) {
        return ResponseEntity.ok(supportChatService.getMessagesForUser(token));
    }

    @PostMapping("send")
    public ResponseEntity<Void> sendSupportMessage(@RequestParam String token, @RequestBody SupportSendMessageDto dto) {
        supportChatService.addUserMessage(token, dto.getMessage());
        return ResponseEntity.ok().build();
    }
}
