package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.entity.user.SupportMessageEntity;
import com.example.MigrosBackend.entity.user.SupportMessageSender;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.exception.shared.SupportSyncConflictException;
import com.example.MigrosBackend.exception.shared.SupportUserBannedException;
import com.example.MigrosBackend.repository.user.SupportMessageEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
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
    private final Clock clock;

    @Autowired
    public SupportModerationService(UserEntityRepository userEntityRepository,
                                    SupportMessageEntityRepository supportMessageEntityRepository,
                                    SupportChatNotificationCoordinator notificationCoordinator,
                                    SupportChatGuards guards,
                                    Clock clock) {
        this.userEntityRepository = userEntityRepository;
        this.supportMessageEntityRepository = supportMessageEntityRepository;
        this.notificationCoordinator = notificationCoordinator;
        this.guards = guards;
        this.clock = clock;
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
        entity.setSender(SupportMessageSender.MANAGEMENT.name());
        entity.setMessage(trimmedMessage);
        entity.setExternalMessageId(SupportChatGuards.safeTrimToNull(externalMessageId));
        entity.setCreatedAt(LocalDateTime.now(clock));
        entity = supportMessageEntityRepository.save(entity);
        notificationCoordinator.broadcastSupportMessageCreated(userMail, SupportMessageSender.MANAGEMENT.name(), entity.getId());
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

        if (!SupportMessageSender.MANAGEMENT.name().equals(entity.getSender())) {
            throw new GeneralException("Only management messages can be edited");
        }

        entity.setMessage(trimmedMessage);
        entity.setEditedAt(LocalDateTime.now(clock));
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

        if (!SupportMessageSender.MANAGEMENT.name().equals(entity.getSender())) {
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
        entity.setEditedAt(LocalDateTime.now(clock));
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

    /**
     * Bans the customer by writing the ban flag alone.
     *
     * <p>The user row also carries the cart list, the profile and the password
     * hash. Loading it and saving it back would write every one of those columns
     * as they were read, so a ban issued while the customer was checking out or
     * editing their profile would quietly undo that work. Naming the single
     * column makes an unrelated update impossible to clobber.
     *
     * <p>The broadcast stays after the write and inside the transaction, as
     * before: listeners only ever see a support-state change, and the ban flag
     * itself is read by the send guard on the next request either way.
     */
    @Transactional
    public void banUser(String userMail) {
        setBanned(userMail, true);
    }

    @Transactional
    public void unbanUser(String userMail) {
        setBanned(userMail, false);
    }

    private void setBanned(String userMail, boolean banned) {
        guards.requireUser(userMail);

        int updated = userEntityRepository.updateBannedByUserMail(userMail, banned);
        if (updated == 0) {
            // The row vanished between the guard's read and this update. The
            // existing not-found error is the accurate report.
            throw new UserNotFoundException(userMail);
        }

        notificationCoordinator.broadcastSupportUpdate(userMail);
    }

    private void assertCanReceiveSupport(UserEntity user) {
        if (Boolean.TRUE.equals(user.getBanned())) {
            throw new SupportUserBannedException("User is banned. Sending messages is disabled.");
        }
    }

    private boolean isEditableByAdmin(String sender) {
        return SupportMessageSender.USER.name().equals(sender)
                || SupportMessageSender.MANAGEMENT.name().equals(sender);
    }

    private String resolveSupportServiceMessageId(SupportMessageEntity entity, String operation) {
        if (SupportMessageSender.USER.name().equals(entity.getSender())) {
            return String.valueOf(entity.getId());
        }

        if (SupportMessageSender.MANAGEMENT.name().equals(entity.getSender())) {
            String externalMessageId = SupportChatGuards.safeTrim(entity.getExternalMessageId());
            if (externalMessageId.isEmpty()) {
                throw new SupportSyncConflictException("This management message is legacy and cannot be synced for " + operation);
            }
            return externalMessageId;
        }

        throw new GeneralException("Unsupported sender type: " + entity.getSender());
    }
}
