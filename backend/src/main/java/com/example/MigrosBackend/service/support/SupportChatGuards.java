package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import org.springframework.stereotype.Component;

/**
 * Shared lookup/validate/trim preamble for the support services.
 *
 * <p>Every support flow repeated the same "find the user or throw" lookup and
 * the same "trim the message or reject an empty one" check. Consolidating them
 * here keeps the flows from drifting apart; the individual flows still decide
 * which checks they apply and in what order, so pre-existing inconsistencies
 * (for example the management edit/delete ban-check asymmetry in
 * {@link SupportModerationService}) are preserved rather than silently fixed.
 */
@Component
final class SupportChatGuards {
    private final UserEntityRepository userEntityRepository;

    SupportChatGuards(UserEntityRepository userEntityRepository) {
        this.userEntityRepository = userEntityRepository;
    }

    UserEntity requireUser(String userMail) {
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        if (user == null) {
            throw new UserNotFoundException(userMail);
        }
        return user;
    }

    String requireMessage(String message) {
        String trimmedMessage = safeTrim(message);
        if (trimmedMessage.isEmpty()) {
            throw new GeneralException("Message cannot be empty");
        }
        return trimmedMessage;
    }

    static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    static String safeTrimToNull(String value) {
        String trimmed = safeTrim(value);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
