package com.example.MigrosBackend.controller.internal;

import com.example.MigrosBackend.dto.support.InternalSupportAgentMessageDto;
import com.example.MigrosBackend.dto.support.InternalSupportDeleteAgentMessageDto;
import com.example.MigrosBackend.dto.support.InternalSupportEditAgentMessageDto;
import com.example.MigrosBackend.dto.support.InternalSupportUserActionDto;
import com.example.MigrosBackend.dto.support.SupportCustomerStatusDto;
import com.example.MigrosBackend.dto.support.SupportCustomerSummaryDto;
import com.example.MigrosBackend.service.support.SupportCustomerDirectoryService;
import com.example.MigrosBackend.service.support.SupportModerationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Backend-only bridge for the external support service. The {@code x-internal-key}
 * header is enforced by {@code InternalApiKeyFilter} before any request reaches
 * this controller, so the handlers only translate HTTP and delegate.
 */
@RestController
@RequestMapping("/internal/support")
public class InternalSupportController {
    private final SupportCustomerDirectoryService supportCustomerDirectoryService;
    private final SupportModerationService supportModerationService;

    public InternalSupportController(SupportCustomerDirectoryService supportCustomerDirectoryService,
                                     SupportModerationService supportModerationService) {
        this.supportCustomerDirectoryService = supportCustomerDirectoryService;
        this.supportModerationService = supportModerationService;
    }

    @GetMapping("customers")
    public ResponseEntity<List<SupportCustomerSummaryDto>> getCustomers(
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        return ResponseEntity.ok(supportCustomerDirectoryService.searchSupportCustomers(query, limit));
    }

    @GetMapping("customer-status")
    public ResponseEntity<SupportCustomerStatusDto> getCustomerStatus(
            @RequestParam(name = "userMail") String userMail
    ) {
        return ResponseEntity.ok(supportCustomerDirectoryService.getCustomerStatus(userMail));
    }

    @PostMapping("agent-message")
    public ResponseEntity<Void> receiveAgentMessage(@Valid @RequestBody InternalSupportAgentMessageDto dto) {
        supportModerationService.addManagementMessage(
                dto.getUserMail().trim(),
                dto.getMessage().trim(),
                dto.getExternalMessageId() == null ? null : dto.getExternalMessageId().trim());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("edit-agent-message")
    public ResponseEntity<Void> editAgentMessage(@Valid @RequestBody InternalSupportEditAgentMessageDto dto) {
        supportModerationService.editManagementMessage(
                dto.getUserMail().trim(),
                dto.getExternalMessageId().trim(),
                dto.getMessage().trim());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("delete-agent-message")
    public ResponseEntity<Void> deleteAgentMessage(@Valid @RequestBody InternalSupportDeleteAgentMessageDto dto) {
        supportModerationService.deleteManagementMessage(
                dto.getUserMail().trim(),
                dto.getExternalMessageId().trim());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("ban-user")
    public ResponseEntity<Void> banUser(@Valid @RequestBody InternalSupportUserActionDto dto) {
        supportModerationService.banUser(dto.getUserMail().trim());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("clear-chat")
    public ResponseEntity<Void> clearChat(@Valid @RequestBody InternalSupportUserActionDto dto) {
        supportModerationService.closeChat(dto.getUserMail().trim());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("unban-user")
    public ResponseEntity<Void> unbanUser(@Valid @RequestBody InternalSupportUserActionDto dto) {
        supportModerationService.unbanUser(dto.getUserMail().trim());
        return ResponseEntity.accepted().build();
    }
}
