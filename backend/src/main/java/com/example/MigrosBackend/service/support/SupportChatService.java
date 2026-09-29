package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.dto.support.SupportCustomerStatusDto;
import com.example.MigrosBackend.dto.support.SupportCustomerSummaryDto;
import com.example.MigrosBackend.dto.user.support.SupportMessageDto;
import com.example.MigrosBackend.entity.user.SupportMessageEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
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
    private final SupportChatNotificationCoordinator notificationCoordinator;

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
        this.notificationCoordinator = new SupportChatNotificationCoordinator(
                supportChatWebSocketHandler,
                supportInternalEventService
        );
    }

    public List<SupportMessageDto> getMessagesForUser(String token) {
        String userMail = getValidUserMailFromToken(token);
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        assertNotBanned(user);

        return getMappedMessagesForUserMail(userMail);
    }

    /**
     * Stores the message and the support-service event owed for it in one
     * transaction.
     *
     * <p>The outbox record is written by {@link SupportInternalEventService}
     * from inside this transaction, so the message and the notification can
     * never disagree: either both commit or neither does. Previously the event
     * was published inline, so a support-service outage left the message
     * committed with its notification silently dropped.
     */
    @Transactional
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

        notificationCoordinator.publishCustomerMessageCreated(entity, userMail);
    }

    public List<SupportMessageDto> getMessagesForUserMail(String userMail) {
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        if (user == null) {
            throw new UserNotFoundException(userMail);
        }

        return getMappedMessagesForUserMail(userMail);
    }

    private List<SupportMessageDto> getMappedMessagesForUserMail(String userMail) {
        return supportMessageEntityRepository.findByUserMailOrderByCreatedAtAscIdAsc(userMail)
                .stream()
                .map(SupportMessageMapper::toDto)
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
                supportMessageEntityRepository.existsByUserMail(normalizedUserMail),
                supportChatWebSocketHandler.isUserOnline(normalizedUserMail)
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

    @Transactional
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
        entity = supportMessageEntityRepository.save(entity);
        notificationCoordinator.broadcastSupportMessageCreated(userMail, "MANAGEMENT", entity.getId());
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
        notificationCoordinator.broadcastSupportUpdate(userMail);
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
        notificationCoordinator.broadcastSupportUpdate(userMail);
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

        notificationCoordinator.publishSupportMessageEdited(userMail, supportServiceMessageId, trimmedMessage);
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
        notificationCoordinator.publishSupportMessageDeleted(userMail, supportServiceMessageId);
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
            notificationCoordinator.broadcastSupportUpdate(userMail);
        }
    }

    public void banUser(String userMail) {
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        if (user == null) {
            throw new UserNotFoundException(userMail);
        }

        user.setBanned(true);
        userEntityRepository.save(user);
        notificationCoordinator.broadcastSupportUpdate(userMail);
    }

    public void unbanUser(String userMail) {
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        if (user == null) {
            throw new UserNotFoundException(userMail);
        }

        user.setBanned(false);
        userEntityRepository.save(user);
        notificationCoordinator.broadcastSupportUpdate(userMail);
    }

    private String getValidUserMailFromToken(String token) {
        String userMail = tokenService.validateAndExtractUser(token);
        UserEntity user = userEntityRepository.findByUserMail(userMail);

        if (user == null) {
            throw new UserNotFoundException(userMail);
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





