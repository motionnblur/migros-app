package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.entity.user.SupportMessageEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.exception.shared.SupportSyncConflictException;
import com.example.MigrosBackend.exception.shared.SupportUserBannedException;
import com.example.MigrosBackend.repository.user.SupportMessageEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin/internal moderation of a customer's chat and account: message
 * edit/delete/close, agent message CRUD reached through the internal bridge,
 * and ban/unban.
 */
@Service
public class SupportModerationService {
    private final UserEntityRepository userEntityRepository;
    private final SupportMessageEntityRepository supportMessageEntityRepository;
    private final SupportChatNotificationCoordinator notificationCoordinator;
    private final SupportChatGuards guards;

    @Autowired
    public SupportModerationService(UserEntityRepository userEntityRepository,
                                    SupportMessageEntityRepository supportMessageEntityRepository,
                                    SupportChatNotificationCoordinator notificationCoordinator,
                                    SupportChatGuards guards) {
        this.userEntityRepository = userEntityRepository;
        this.supportMessageEntityRepository = supportMessageEntityRepository;
        this.notificationCoordinator = notificationCoordinator;
        this.guards = guards;
    }

    public void addManagementMessage(String userMail, String message) {
        addManagementMessage(userMail, message, null);
    }

    @Transactional
    public void addManagementMessage(String userMail, String message, String externalMessageId) {
        UserEntity user = guards.requireUser(userMail);

        assertCanReceiveSupport(user);

        String trimmedMessage = guards.requireMessage(message);

        SupportMessageEntity entity = new SupportMessageEntity();
        entity.setUserMail(userMail);
        entity.setSender("MANAGEMENT");
        entity.setMessage(trimmedMessage);
        entity.setExternalMessageId(SupportChatGuards.safeTrimToNull(externalMessageId));
        entity.setCreatedAt(LocalDateTime.now());
        entity = supportMessageEntityRepository.save(entity);
        notificationCoordinator.broadcastSupportMessageCreated(userMail, "MANAGEMENT", entity.getId());
    }

    // Pre-existing ban-check asymmetry: addManagementMessage refuses to write
    // for a banned customer (assertCanReceiveSupport), but edit/delete of an
    // existing management message never consults the ban flag. Preserved here
    // intentionally so behavior is unchanged; do not "fix" it without a product
    // decision.
    @Transactional
    public void editManagementMessage(String userMail, String externalMessageId, String message) {
        guards.requireUser(userMail);

        String trimmedExternalMessageId = SupportChatGuards.safeTrim(externalMessageId);
        if (trimmedExternalMessageId.isEmpty()) {
            throw new GeneralException("externalMessageId is required");
        }

        String trimmedMessage = guards.requireMessage(message);

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
        guards.requireUser(userMail);

        String trimmedExternalMessageId = SupportChatGuards.safeTrim(externalMessageId);
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
        guards.requireUser(userMail);

        if (messageId == null || messageId <= 0) {
            throw new GeneralException("messageId is required");
        }

        String trimmedMessage = guards.requireMessage(message);

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
        guards.requireUser(userMail);

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
        guards.requireUser(userMail);

        List<SupportMessageEntity> messages = supportMessageEntityRepository.findByUserMailOrderByCreatedAtAscIdAsc(userMail);
        if (!messages.isEmpty()) {
            supportMessageEntityRepository.deleteAllInBatch(messages);
            notificationCoordinator.broadcastSupportUpdate(userMail);
        }
    }

    public void banUser(String userMail) {
        UserEntity user = guards.requireUser(userMail);

        user.setBanned(true);
        userEntityRepository.save(user);
        notificationCoordinator.broadcastSupportUpdate(userMail);
    }

    public void unbanUser(String userMail) {
        UserEntity user = guards.requireUser(userMail);

        user.setBanned(false);
        userEntityRepository.save(user);
        notificationCoordinator.broadcastSupportUpdate(userMail);
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
            String externalMessageId = SupportChatGuards.safeTrim(entity.getExternalMessageId());
            if (externalMessageId.isEmpty()) {
                throw new SupportSyncConflictException("This management message is legacy and cannot be synced for " + operation);
            }
            return externalMessageId;
        }

        throw new GeneralException("Unsupported sender type: " + entity.getSender());
    }
}
