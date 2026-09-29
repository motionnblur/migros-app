package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.dto.user.support.SupportMessageDto;
import com.example.MigrosBackend.entity.user.SupportMessageEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.user.SupportMessageEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * User-side live chat: token-validated conversation reads and message writes.
 */
@Service
public class UserSupportChatService {
    private final SupportMessageEntityRepository supportMessageEntityRepository;
    private final TokenService tokenService;
    private final SupportChatNotificationCoordinator notificationCoordinator;
    private final SupportChatGuards guards;

    @Autowired
    public UserSupportChatService(SupportMessageEntityRepository supportMessageEntityRepository,
                                  TokenService tokenService,
                                  SupportChatNotificationCoordinator notificationCoordinator,
                                  SupportChatGuards guards) {
        this.supportMessageEntityRepository = supportMessageEntityRepository;
        this.tokenService = tokenService;
        this.notificationCoordinator = notificationCoordinator;
        this.guards = guards;
    }

    public List<SupportMessageDto> getMessagesForUser(String token) {
        String userMail = getValidUserMailFromToken(token);
        UserEntity user = guards.requireUser(userMail);
        assertNotBanned(user);

        return getMappedMessagesForUserMail(userMail);
    }

    /**
     * Stores the message and the support-service event owed for it in one
     * transaction.
     *
     * <p>The outbox record is written by {@link SupportInternalEventService}
     * through the notification coordinator from inside this transaction, so the
     * message and the notification can never disagree: either both commit or
     * neither does.
     */
    @Transactional
    public void addUserMessage(String token, String message) {
        String userMail = getValidUserMailFromToken(token);
        UserEntity user = guards.requireUser(userMail);
        assertNotBanned(user);

        String trimmedMessage = guards.requireMessage(message);

        SupportMessageEntity entity = new SupportMessageEntity();
        entity.setUserMail(userMail);
        entity.setSender("USER");
        entity.setMessage(trimmedMessage);
        entity.setCreatedAt(LocalDateTime.now());
        entity = supportMessageEntityRepository.save(entity);

        notificationCoordinator.publishCustomerMessageCreated(entity, userMail);
    }

    public List<SupportMessageDto> getMessagesForUserMail(String userMail) {
        guards.requireUser(userMail);

        return getMappedMessagesForUserMail(userMail);
    }

    private List<SupportMessageDto> getMappedMessagesForUserMail(String userMail) {
        return SupportMessageMapper.toDtos(
                supportMessageEntityRepository.findByUserMailOrderByCreatedAtAscIdAsc(userMail));
    }

    private String getValidUserMailFromToken(String token) {
        String userMail = tokenService.validateAndExtractUser(token);
        guards.requireUser(userMail);

        return userMail;
    }

    private void assertNotBanned(UserEntity user) {
        if (Boolean.TRUE.equals(user.getBanned())) {
            throw new GeneralException("You are banned from live support.");
        }
    }
}
