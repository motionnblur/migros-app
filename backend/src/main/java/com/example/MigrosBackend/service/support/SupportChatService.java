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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SupportChatService {
    private final SupportMessageEntityRepository supportMessageEntityRepository;
    private final UserEntityRepository userEntityRepository;
    private final TokenService tokenService;

    @Autowired
    public SupportChatService(SupportMessageEntityRepository supportMessageEntityRepository,
                              UserEntityRepository userEntityRepository,
                              TokenService tokenService) {
        this.supportMessageEntityRepository = supportMessageEntityRepository;
        this.userEntityRepository = userEntityRepository;
        this.tokenService = tokenService;
    }

    public List<SupportMessageDto> getMessagesForUser(String token) {
        String userMail = getValidUserMailFromToken(token);

        return supportMessageEntityRepository.findByUserMailOrderByCreatedAtAscIdAsc(userMail)
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    public void addUserMessage(String token, String message) {
        String userMail = getValidUserMailFromToken(token);
        String trimmedMessage = message == null ? "" : message.trim();

        if (trimmedMessage.isEmpty()) {
            throw new GeneralException("Message cannot be empty");
        }

        SupportMessageEntity entity = new SupportMessageEntity();
        entity.setUserMail(userMail);
        entity.setSender("USER");
        entity.setMessage(trimmedMessage);
        supportMessageEntityRepository.save(entity);
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

    public void addManagementMessage(String userMail, String message) {
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        if (user == null) {
            throw new UserNotFoundException(userMail);
        }

        String trimmedMessage = message == null ? "" : message.trim();
        if (trimmedMessage.isEmpty()) {
            throw new GeneralException("Message cannot be empty");
        }

        SupportMessageEntity entity = new SupportMessageEntity();
        entity.setUserMail(userMail);
        entity.setSender("MANAGEMENT");
        entity.setMessage(trimmedMessage);
        supportMessageEntityRepository.save(entity);
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

    private SupportMessageDto mapToDto(SupportMessageEntity entity) {
        return new SupportMessageDto(
                entity.getId(),
                entity.getSender(),
                entity.getMessage(),
                entity.getCreatedAt()
        );
    }
}
