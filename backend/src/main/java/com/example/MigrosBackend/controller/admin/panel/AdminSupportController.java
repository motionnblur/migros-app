package com.example.MigrosBackend.controller.admin.panel;

import com.example.MigrosBackend.dto.admin.panel.SupportAdminMessageDto;
import com.example.MigrosBackend.dto.support.SupportCustomerSummaryDto;
import com.example.MigrosBackend.dto.user.support.SupportMessageDto;
import com.example.MigrosBackend.service.support.SupportCustomerDirectoryService;
import com.example.MigrosBackend.service.support.SupportModerationService;
import com.example.MigrosBackend.service.support.UserSupportChatService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("admin/panel")
public class AdminSupportController {
    private final UserSupportChatService userSupportChatService;
    private final SupportCustomerDirectoryService supportCustomerDirectoryService;
    private final SupportModerationService supportModerationService;

    @Autowired
    public AdminSupportController(UserSupportChatService userSupportChatService,
                                  SupportCustomerDirectoryService supportCustomerDirectoryService,
                                  SupportModerationService supportModerationService) {
        this.userSupportChatService = userSupportChatService;
        this.supportCustomerDirectoryService = supportCustomerDirectoryService;
        this.supportModerationService = supportModerationService;
    }

    @GetMapping("support/users")
    public ResponseEntity<List<String>> getSupportUsers() {
        return ResponseEntity.ok(supportCustomerDirectoryService.getSupportUserMails());
    }

    @GetMapping("support/customers")
    public ResponseEntity<List<SupportCustomerSummaryDto>> getSupportCustomers(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer limit
    ) {
        return ResponseEntity.ok(supportCustomerDirectoryService.searchSupportCustomers(query, limit));
    }

    @GetMapping("support/banned-users")
    public ResponseEntity<List<String>> getBannedSupportUsers() {
        return ResponseEntity.ok(supportCustomerDirectoryService.getBannedUserMails());
    }

    @GetMapping("support/messages")
    public ResponseEntity<List<SupportMessageDto>> getSupportMessages(@RequestParam String userMail) {
        return ResponseEntity.ok(userSupportChatService.getMessagesForUserMail(userMail));
    }

    @PostMapping("support/reply")
    public ResponseEntity<Void> sendSupportReply(@Valid @RequestBody SupportAdminMessageDto dto) {
        supportModerationService.addManagementMessage(dto.getUserMail(), dto.getMessage());
        return ResponseEntity.ok().build();
    }

    @PatchMapping("support/messages/{messageId}")
    public ResponseEntity<Void> editSupportMessage(
            @PathVariable Long messageId,
            @Valid @RequestBody SupportAdminMessageDto dto
    ) {
        supportModerationService.editMessageForAdmin(dto.getUserMail(), messageId, dto.getMessage());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("support/messages/{messageId}")
    public ResponseEntity<Void> deleteSupportMessage(
            @PathVariable Long messageId,
            @RequestParam String userMail
    ) {
        supportModerationService.deleteMessageForAdmin(userMail, messageId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("support/close")
    public ResponseEntity<Void> closeSupportChatDelete(@RequestParam String userMail) {
        supportModerationService.closeChat(userMail);
        return ResponseEntity.ok().build();
    }

    @PostMapping("support/close")
    public ResponseEntity<Void> closeSupportChatPost(@RequestParam String userMail) {
        supportModerationService.closeChat(userMail);
        return ResponseEntity.ok().build();
    }

    @PostMapping("support/ban")
    public ResponseEntity<Void> banSupportUser(@RequestParam String userMail) {
        supportModerationService.banUser(userMail);
        return ResponseEntity.ok().build();
    }

    @PostMapping("support/unban")
    public ResponseEntity<Void> unbanSupportUser(@RequestParam String userMail) {
        supportModerationService.unbanUser(userMail);
        return ResponseEntity.ok().build();
    }
}
