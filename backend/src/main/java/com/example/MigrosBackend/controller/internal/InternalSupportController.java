package com.example.MigrosBackend.controller.internal;

import com.example.MigrosBackend.dto.support.InternalSupportAgentMessageDto;
import com.example.MigrosBackend.dto.support.InternalSupportDeleteAgentMessageDto;
import com.example.MigrosBackend.dto.support.InternalSupportEditAgentMessageDto;
import com.example.MigrosBackend.dto.support.InternalSupportUserActionDto;
import com.example.MigrosBackend.dto.support.SupportCustomerStatusDto;
import com.example.MigrosBackend.dto.support.SupportCustomerSummaryDto;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.service.support.SupportChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("internal/support")
public class InternalSupportController {
    private final SupportChatService supportChatService;
    private final String supportInternalKey;

    public InternalSupportController(
            SupportChatService supportChatService,
            @Value("${support.internal.key:}") String supportInternalKey
    ) {
        this.supportChatService = supportChatService;
        this.supportInternalKey = supportInternalKey;
    }

    @GetMapping("customers")
    public ResponseEntity<List<SupportCustomerSummaryDto>> getCustomers(
            @RequestHeader(name = "x-internal-key", required = false) String internalKey,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        if (!isAuthorized(internalKey)) {
            return ResponseEntity.status(401).build();
        }

        return ResponseEntity.ok(supportChatService.searchSupportCustomers(query, limit));
    }

    @GetMapping("customer-status")
    public ResponseEntity<SupportCustomerStatusDto> getCustomerStatus(
            @RequestHeader(name = "x-internal-key", required = false) String internalKey,
            @RequestParam(name = "userMail") String userMail
    ) {
        if (!isAuthorized(internalKey)) {
            return ResponseEntity.status(401).build();
        }

        return ResponseEntity.ok(supportChatService.getCustomerStatus(userMail));
    }

    @PostMapping("agent-message")
    public ResponseEntity<Void> receiveAgentMessage(
            @RequestHeader(name = "x-internal-key", required = false) String internalKey,
            @RequestBody InternalSupportAgentMessageDto dto
    ) {
        if (!isAuthorized(internalKey)) {
            return ResponseEntity.status(401).build();
        }

        String userMail = safeTrim(dto.getUserMail());
        String message = safeTrim(dto.getMessage());

        if (userMail.isEmpty() || message.isEmpty()) {
            throw new GeneralException("userMail and message are required");
        }

        supportChatService.addManagementMessage(userMail, message, safeTrimToNull(dto.getExternalMessageId()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("edit-agent-message")
    public ResponseEntity<Void> editAgentMessage(
            @RequestHeader(name = "x-internal-key", required = false) String internalKey,
            @RequestBody InternalSupportEditAgentMessageDto dto
    ) {
        if (!isAuthorized(internalKey)) {
            return ResponseEntity.status(401).build();
        }

        String userMail = safeTrim(dto.getUserMail());
        String externalMessageId = safeTrim(dto.getExternalMessageId());
        String message = safeTrim(dto.getMessage());

        if (userMail.isEmpty() || externalMessageId.isEmpty() || message.isEmpty()) {
            throw new GeneralException("userMail, externalMessageId and message are required");
        }

        supportChatService.editManagementMessage(userMail, externalMessageId, message);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("delete-agent-message")
    public ResponseEntity<Void> deleteAgentMessage(
            @RequestHeader(name = "x-internal-key", required = false) String internalKey,
            @RequestBody InternalSupportDeleteAgentMessageDto dto
    ) {
        if (!isAuthorized(internalKey)) {
            return ResponseEntity.status(401).build();
        }

        String userMail = safeTrim(dto.getUserMail());
        String externalMessageId = safeTrim(dto.getExternalMessageId());

        if (userMail.isEmpty() || externalMessageId.isEmpty()) {
            throw new GeneralException("userMail and externalMessageId are required");
        }

        supportChatService.deleteManagementMessage(userMail, externalMessageId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("ban-user")
    public ResponseEntity<Void> banUser(
            @RequestHeader(name = "x-internal-key", required = false) String internalKey,
            @RequestBody InternalSupportUserActionDto dto
    ) {
        if (!isAuthorized(internalKey)) {
            return ResponseEntity.status(401).build();
        }

        String userMail = safeTrim(dto.getUserMail());
        if (userMail.isEmpty()) {
            throw new GeneralException("userMail is required");
        }

        supportChatService.banUser(userMail);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("clear-chat")
    public ResponseEntity<Void> clearChat(
            @RequestHeader(name = "x-internal-key", required = false) String internalKey,
            @RequestBody InternalSupportUserActionDto dto
    ) {
        if (!isAuthorized(internalKey)) {
            return ResponseEntity.status(401).build();
        }

        String userMail = safeTrim(dto.getUserMail());
        if (userMail.isEmpty()) {
            throw new GeneralException("userMail is required");
        }

        supportChatService.closeChat(userMail);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("unban-user")
    public ResponseEntity<Void> unbanUser(
            @RequestHeader(name = "x-internal-key", required = false) String internalKey,
            @RequestBody InternalSupportUserActionDto dto
    ) {
        if (!isAuthorized(internalKey)) {
            return ResponseEntity.status(401).build();
        }

        String userMail = safeTrim(dto.getUserMail());
        if (userMail.isEmpty()) {
            throw new GeneralException("userMail is required");
        }

        supportChatService.unbanUser(userMail);
        return ResponseEntity.accepted().build();
    }

    private boolean isAuthorized(String internalKey) {
        return supportInternalKey == null
                || supportInternalKey.isBlank()
                || supportInternalKey.equals(internalKey);
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeTrimToNull(String value) {
        String trimmed = safeTrim(value);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
