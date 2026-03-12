package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.dto.user.support.SupportMessageDto;
import com.example.MigrosBackend.entity.user.SupportMessageEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.exception.shared.InvalidTokenException;
import com.example.MigrosBackend.repository.user.SupportMessageEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.websocket.SupportChatWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SupportChatService {
    private final SupportMessageEntityRepository supportMessageEntityRepository;
    private final UserEntityRepository userEntityRepository;
    private final TokenService tokenService;
    private final SupportChatWebSocketHandler supportChatWebSocketHandler;
    private final SupportInternalEventService supportInternalEventService;

    @Autowired
    public SupportChatService(SupportMessageEntityRepository supportMessageEntityRepository,
                              UserEntityRepository userEntityRepository,
                              TokenService tokenService,
                              SupportChatWebSocketHandler supportChatWebSocketHandler,
                              SupportInternalEventService supportInternalEventService) {
        this.supportMessageEntityRepository = supportMessageEntityRepository;
        this.userEntityRepository = userEntityRepository;
        this.tokenService = tokenService;
        this.supportChatWebSocketHandler = supportChatWebSocketHandler;
        this.supportInternalEventService = supportInternalEventService;
    }

    public List<SupportMessageDto> getMessagesForUser(String token) {
        String userMail = getValidUserMailFromToken(token);
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        assertNotBanned(user);

        return supportMessageEntityRepository.findByUserMailOrderByCreatedAtAscIdAsc(userMail)
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    public void addUserMessage(String token, String message) {
        String userMail = getValidUserMailFromToken(token);
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        assertNotBanned(user);

        String trimmedMessage = safeTrim(message);
        if (trimmedMessage.isEmpty()) {
            throw new GeneralException("Message cannot be empty");
        }

        SupportMessageEntity entity = new SupportMessageEntity();
        entity.setUserMail(userMail);
        entity.setSender("USER");
        entity.setMessage(trimmedMessage);
        entity = supportMessageEntityRepository.save(entity);

        supportInternalEventService.publishCustomerMessageCreated(entity);
        supportChatWebSocketHandler.broadcastSupportUpdate(userMail);
    }

    public List<SupportMessageDto> getMessagesForUserMail(String userMail) {
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        if (user == null) {
            throw new UserNotFoundException(userMail);
        }

        return supportMessageEntityRepository.findByUserMailOrderByCreatedAtAscIdAsc(userMail)
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    public List<String> getSupportUserMails() {
        return supportMessageEntityRepository.findDistinctUserMails();
    }

    public List<String> getBannedUserMails() {
        return userEntityRepository.findByBannedTrueOrderByUserMailAsc().stream()
                .map(UserEntity::getUserMail)
                .toList();
    }

    public void addManagementMessage(String userMail, String message) {
        addManagementMessage(userMail, message, null);
    }

    public void addManagementMessage(String userMail, String message, String externalMessageId) {
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        if (user == null) {
            throw new UserNotFoundException(userMail);
        }

        String trimmedMessage = safeTrim(message);
        if (trimmedMessage.isEmpty()) {
            throw new GeneralException("Message cannot be empty");
        }

        SupportMessageEntity entity = new SupportMessageEntity();
        entity.setUserMail(userMail);
        entity.setSender("MANAGEMENT");
        entity.setMessage(trimmedMessage);
        entity.setExternalMessageId(safeTrimToNull(externalMessageId));
        supportMessageEntityRepository.save(entity);
        supportChatWebSocketHandler.broadcastSupportUpdate(userMail);
    }

    @Transactional
    public void editManagementMessage(String userMail, String externalMessageId, String message) {
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        if (user == null) {
            throw new UserNotFoundException(userMail);
        }

        String trimmedExternalMessageId = safeTrim(externalMessageId);
        if (trimmedExternalMessageId.isEmpty()) {
            throw new GeneralException("externalMessageId is required");
        }

        String trimmedMessage = safeTrim(message);
        if (trimmedMessage.isEmpty()) {
            throw new GeneralException("Message cannot be empty");
        }

        SupportMessageEntity entity = supportMessageEntityRepository
                .findByUserMailAndExternalMessageId(userMail, trimmedExternalMessageId)
                .orElseThrow(() -> new GeneralException("Editable support message not found"));

        if (!"MANAGEMENT".equals(entity.getSender())) {
            throw new GeneralException("Only management messages can be edited");
        }

        entity.setMessage(trimmedMessage);
        entity.setEditedAt(LocalDateTime.now());
        supportMessageEntityRepository.save(entity);
        supportChatWebSocketHandler.broadcastSupportUpdate(userMail);
    }

    @Transactional
    public void closeChat(String userMail) {
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        if (user == null) {
            throw new UserNotFoundException(userMail);
        }

        List<SupportMessageEntity> messages = supportMessageEntityRepository.findByUserMailOrderByCreatedAtAscIdAsc(userMail);
        if (!messages.isEmpty()) {
            supportMessageEntityRepository.deleteAllInBatch(messages);
            supportChatWebSocketHandler.broadcastSupportUpdate(userMail);
        }
    }

    public void banUser(String userMail) {
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        if (user == null) {
            throw new UserNotFoundException(userMail);
        }

        user.setBanned(true);
        userEntityRepository.save(user);
        supportChatWebSocketHandler.broadcastSupportUpdate(userMail);
    }

    public void unbanUser(String userMail) {
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        if (user == null) {
            throw new UserNotFoundException(userMail);
        }

        user.setBanned(false);
        userEntityRepository.save(user);
        supportChatWebSocketHandler.broadcastSupportUpdate(userMail);
    }

    private String getValidUserMailFromToken(String token) {
        String userMail = tokenService.extractUsername(token);
        UserEntity user = userEntityRepository.findByUserMail(userMail);

        if (user == null) {
            throw new UserNotFoundException(userMail);
        }

        if (!tokenService.validateToken(token, user.getUserMail())) {
            throw new InvalidTokenException();
        }

        return userMail;
    }

    private void assertNotBanned(UserEntity user) {
        if (Boolean.TRUE.equals(user.getBanned())) {
            throw new GeneralException("You are banned from live support.");
        }
    }

    private SupportMessageDto mapToDto(SupportMessageEntity entity) {
        return new SupportMessageDto(
                entity.getId(),
                entity.getSender(),
                entity.getMessage(),
                entity.getCreatedAt(),
                entity.getEditedAt()
        );
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeTrimToNull(String value) {
        String trimmed = safeTrim(value);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
