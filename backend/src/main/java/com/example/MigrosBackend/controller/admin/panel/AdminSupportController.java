package com.example.MigrosBackend.controller.admin.panel;

import com.example.MigrosBackend.dto.admin.panel.SupportReplyDto;
import com.example.MigrosBackend.dto.user.support.SupportMessageDto;
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
@RequestMapping("admin/panel")
public class AdminSupportController {
    private final SupportChatService supportChatService;

    @Autowired
    public AdminSupportController(SupportChatService supportChatService) {
        this.supportChatService = supportChatService;
    }

    @GetMapping("support/users")
    public ResponseEntity<List<String>> getSupportUsers() {
        return ResponseEntity.ok(supportChatService.getSupportUserMails());
    }

    @GetMapping("support/messages")
    public ResponseEntity<List<SupportMessageDto>> getSupportMessages(@RequestParam String userMail) {
        return ResponseEntity.ok(supportChatService.getMessagesForUserMail(userMail));
    }

    @PostMapping("support/reply")
    public ResponseEntity<Void> sendSupportReply(@RequestBody SupportReplyDto dto) {
        supportChatService.addManagementMessage(dto.getUserMail(), dto.getMessage());
        return ResponseEntity.ok().build();
    }
}


