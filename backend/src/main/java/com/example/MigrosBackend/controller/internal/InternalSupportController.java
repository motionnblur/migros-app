package com.example.MigrosBackend.controller.internal;

import com.example.MigrosBackend.dto.support.InternalSupportAgentMessageDto;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.service.support.SupportChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping("agent-message")
    public ResponseEntity<Void> receiveAgentMessage(
            @RequestHeader(name = "x-internal-key", required = false) String internalKey,
            @RequestBody InternalSupportAgentMessageDto dto
    ) {
        if (supportInternalKey != null && !supportInternalKey.isBlank() && !supportInternalKey.equals(internalKey)) {
            return ResponseEntity.status(401).build();
        }

        String userMail = dto.getUserMail() == null ? "" : dto.getUserMail().trim();
        String message = dto.getMessage() == null ? "" : dto.getMessage().trim();

        if (userMail.isEmpty() || message.isEmpty()) {
            throw new GeneralException("userMail and message are required");
        }

        supportChatService.addManagementMessage(userMail, message);
        return ResponseEntity.accepted().build();
    }
}
