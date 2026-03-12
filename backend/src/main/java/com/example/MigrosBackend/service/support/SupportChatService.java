package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.dto.support.SupportCustomerStatusDto;
import com.example.MigrosBackend.dto.support.SupportCustomerSummaryDto;
import com.example.MigrosBackend.dto.user.support.SupportMessageDto;
import com.example.MigrosBackend.entity.user.SupportMessageEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.exception.shared.InvalidTokenException;
import com.example.MigrosBackend.exception.shared.SupportSyncConflictException;
import com.example.MigrosBackend.exception.shared.SupportUserBannedException;
import com.example.MigrosBackend.repository.user.SupportMessageEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.websocket.SupportChatWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    public List<SupportCustomerSummaryDto> searchSupportCustomers(String query, Integer limit) {
        int normalizedLimit = normalizeSupportSearchLimit(limit);
        String normalizedQuery = safeTrimToNull(query);

        List<UserEntity> users = userEntityRepository.searchForSupportCustomers(
                normalizedQuery,
                PageRequest.of(0, normalizedLimit)
        );

        List<String> userMails = users.stream()
                .map(UserEntity::getUserMail)
                .filter(mail -> mail != null && !mail.isBlank())
                .toList();

        Set<String> mailsWithConversation = new HashSet<>();
        if (!userMails.isEmpty()) {
            mailsWithConversation.addAll(supportMessageEntityRepository.findDistinctUserMailsIn(userMails));
        }

        return users.stream()
                .map(user -> new SupportCustomerSummaryDto(
                        user.getUserMail(),
                        user.getUserName(),
                        user.getUserLastName(),
                        Boolean.TRUE.equals(user.getBanned()),
                        mailsWithConversation.contains(user.getUserMail())
                ))
                .toList();
    }

    public SupportCustomerStatusDto getCustomerStatus(String userMail) {
        String normalizedUserMail = safeTrim(userMail);
        if (normalizedUserMail.isEmpty()) {
            throw new GeneralException("userMail is required");
        }

        UserEntity user = userEntityRepository.findByUserMail(normalizedUserMail);
        if (user == null) {
            throw new UserNotFoundException(normalizedUserMail);
        }

        return new SupportCustomerStatusDto(
                normalizedUserMail,
                Boolean.TRUE.equals(user.getBanned()),
                supportMessageEntityRepository.existsByUserMail(normalizedUserMail)
        );
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

        assertCanReceiveSupport(user);

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
    public void deleteManagementMessage(String userMail, String externalMessageId) {
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        if (user == null) {
            throw new UserNotFoundException(userMail);
        }

        String trimmedExternalMessageId = safeTrim(externalMessageId);
        if (trimmedExternalMessageId.isEmpty()) {
            throw new GeneralException("externalMessageId is required");
        }

        SupportMessageEntity entity = supportMessageEntityRepository
                .findByUserMailAndExternalMessageId(userMail, trimmedExternalMessageId)
                .orElseThrow(() -> new GeneralException("Deletable support message not found"));

        if (!"MANAGEMENT".equals(entity.getSender())) {
            throw new GeneralException("Only management messages can be deleted");
        }

        supportMessageEntityRepository.delete(entity);
        supportChatWebSocketHandler.broadcastSupportUpdate(userMail);
    }

    @Transactional
    public void editMessageForAdmin(String userMail, Long messageId, String message) {
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        if (user == null) {
            throw new UserNotFoundException(userMail);
        }

        if (messageId == null || messageId <= 0) {
            throw new GeneralException("messageId is required");
        }

        String trimmedMessage = safeTrim(message);
        if (trimmedMessage.isEmpty()) {
            throw new GeneralException("Message cannot be empty");
        }

        SupportMessageEntity entity = supportMessageEntityRepository
                .findByIdAndUserMail(messageId, userMail)
                .orElseThrow(() -> new GeneralException("Support message not found"));

        if (!isEditableByAdmin(entity.getSender())) {
            throw new GeneralException("Only USER and MANAGEMENT messages can be edited");
        }

        String supportServiceMessageId = resolveSupportServiceMessageId(entity, "edit");

        entity.setMessage(trimmedMessage);
        entity.setEditedAt(LocalDateTime.now());
        supportMessageEntityRepository.save(entity);

        supportInternalEventService.publishSupportMessageEdited(userMail, supportServiceMessageId, trimmedMessage);
        supportChatWebSocketHandler.broadcastSupportUpdate(userMail);
    }

    @Transactional
    public void deleteMessageForAdmin(String userMail, Long messageId) {
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        if (user == null) {
            throw new UserNotFoundException(userMail);
        }

        if (messageId == null || messageId <= 0) {
            throw new GeneralException("messageId is required");
        }

        SupportMessageEntity entity = supportMessageEntityRepository
                .findByIdAndUserMail(messageId, userMail)
                .orElseThrow(() -> new GeneralException("Support message not found"));

        if (!isEditableByAdmin(entity.getSender())) {
            throw new GeneralException("Only USER and MANAGEMENT messages can be deleted");
        }

        String supportServiceMessageId = resolveSupportServiceMessageId(entity, "delete");

        supportMessageEntityRepository.delete(entity);
        supportInternalEventService.publishSupportMessageDeleted(userMail, supportServiceMessageId);
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

    private void assertCanReceiveSupport(UserEntity user) {
        if (Boolean.TRUE.equals(user.getBanned())) {
            throw new SupportUserBannedException("User is banned. Sending messages is disabled.");
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

    private boolean isEditableByAdmin(String sender) {
        return "USER".equals(sender) || "MANAGEMENT".equals(sender);
    }

    private String resolveSupportServiceMessageId(SupportMessageEntity entity, String operation) {
        if ("USER".equals(entity.getSender())) {
            return String.valueOf(entity.getId());
        }

        if ("MANAGEMENT".equals(entity.getSender())) {
            String externalMessageId = safeTrim(entity.getExternalMessageId());
            if (externalMessageId.isEmpty()) {
                throw new SupportSyncConflictException("This management message is legacy and cannot be synced for " + operation);
            }
            return externalMessageId;
        }

        throw new GeneralException("Unsupported sender type: " + entity.getSender());
    }

    private int normalizeSupportSearchLimit(Integer limit) {
        if (limit == null) {
            return 20;
        }

        if (limit < 1) {
            return 1;
        }

        return Math.min(limit, 100);
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeTrimToNull(String value) {
        String trimmed = safeTrim(value);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
