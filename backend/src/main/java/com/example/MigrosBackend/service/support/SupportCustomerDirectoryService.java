package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.dto.support.SupportCustomerStatusDto;
import com.example.MigrosBackend.dto.support.SupportCustomerSummaryDto;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.user.SupportMessageEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.websocket.SupportChatWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Customer directory and presence reads for the admin panel and the internal
 * bridge: search, per-customer status, and the mail lists backing the panel.
 */
@Service
public class SupportCustomerDirectoryService {
    private final UserEntityRepository userEntityRepository;
    private final SupportMessageEntityRepository supportMessageEntityRepository;
    private final SupportChatWebSocketHandler supportChatWebSocketHandler;
    private final SupportChatGuards guards;

    @Autowired
    public SupportCustomerDirectoryService(UserEntityRepository userEntityRepository,
                                           SupportMessageEntityRepository supportMessageEntityRepository,
                                           SupportChatWebSocketHandler supportChatWebSocketHandler,
                                           SupportChatGuards guards) {
        this.userEntityRepository = userEntityRepository;
        this.supportMessageEntityRepository = supportMessageEntityRepository;
        this.supportChatWebSocketHandler = supportChatWebSocketHandler;
        this.guards = guards;
    }

    public List<SupportCustomerSummaryDto> searchSupportCustomers(String query, Integer limit) {
        int normalizedLimit = normalizeSupportSearchLimit(limit);
        String normalizedQuery = SupportChatGuards.safeTrimToNull(query);

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
        String normalizedUserMail = SupportChatGuards.safeTrim(userMail);
        if (normalizedUserMail.isEmpty()) {
            throw new GeneralException("userMail is required");
        }

        UserEntity user = guards.requireUser(normalizedUserMail);

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

    private int normalizeSupportSearchLimit(Integer limit) {
        if (limit == null) {
            return 20;
        }

        if (limit < 1) {
            return 1;
        }

        return Math.min(limit, 100);
    }
}
