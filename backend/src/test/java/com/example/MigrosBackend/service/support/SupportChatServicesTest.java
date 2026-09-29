package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.dto.user.support.SupportMessageDto;
import com.example.MigrosBackend.entity.user.SupportMessageEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.user.SupportMessageEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.websocket.SupportChatWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupportChatServicesTest {
    private static final String USER_MAIL = "user@mail.com";

    @Mock
    private SupportMessageEntityRepository supportMessageEntityRepository;
    @Mock
    private UserEntityRepository userEntityRepository;
    @Mock
    private SupportChatWebSocketHandler supportChatWebSocketHandler;
    @Mock
    private SupportInternalEventService supportInternalEventService;

    private UserSupportChatService userSupportChatService;
    private SupportModerationService supportModerationService;
    private SupportCustomerDirectoryService supportCustomerDirectoryService;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        user.setId(1L);
        user.setUserMail(USER_MAIL);
        user.setBanned(false);

        SupportChatGuards guards = new SupportChatGuards(userEntityRepository);
        SupportChatNotificationCoordinator notificationCoordinator = new SupportChatNotificationCoordinator(
                supportChatWebSocketHandler, supportInternalEventService);
        userSupportChatService = new UserSupportChatService(
                supportMessageEntityRepository,
                notificationCoordinator,
                guards,
                Clock.systemDefaultZone());
        supportModerationService = new SupportModerationService(
                userEntityRepository,
                supportMessageEntityRepository,
                notificationCoordinator,
                guards,
                Clock.systemDefaultZone());
        supportCustomerDirectoryService = new SupportCustomerDirectoryService(
                userEntityRepository,
                supportMessageEntityRepository,
                supportChatWebSocketHandler,
                guards);
    }

    @Test
    void getMessagesForUser_shouldReturnMappedDtos_whenUserNotBanned() {
        SupportMessageEntity first = new SupportMessageEntity(1L, USER_MAIL, "USER", "Hello", LocalDateTime.now().minusMinutes(1));
        SupportMessageEntity second = new SupportMessageEntity(2L, USER_MAIL, "MANAGEMENT", "Hi there", LocalDateTime.now());

        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);
        when(supportMessageEntityRepository.findByUserMailOrderByCreatedAtAscIdAsc(USER_MAIL))
                .thenReturn(Arrays.asList(first, second));

        List<SupportMessageDto> result = userSupportChatService.getMessagesForUser(USER_MAIL);

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getId());
        assertEquals("USER", result.get(0).getSender());
        assertEquals("Hello", result.get(0).getMessage());
        assertEquals(2L, result.get(1).getId());
        assertEquals("MANAGEMENT", result.get(1).getSender());
    }

    @Test
    void getMessagesForUser_shouldThrowGeneralException_whenUserBanned() {
        user.setBanned(true);
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);

        GeneralException ex = assertThrows(GeneralException.class, () -> userSupportChatService.getMessagesForUser(USER_MAIL));
        assertEquals("You are banned from live support.", ex.getMessage());
    }

    @Test
    void addUserMessage_shouldThrowGeneralException_whenMessageBlank() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);

        assertThrows(GeneralException.class, () -> userSupportChatService.addUserMessage(USER_MAIL, "   "));
        verify(supportMessageEntityRepository, never()).save(any());
        verify(supportChatWebSocketHandler, never()).broadcastSupportUpdate(any());
    }

    @Test
    void addUserMessage_shouldTrimAndPersist_andBroadcast() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);

        userSupportChatService.addUserMessage(USER_MAIL, "  hello  ");

        ArgumentCaptor<SupportMessageEntity> captor = ArgumentCaptor.forClass(SupportMessageEntity.class);
        verify(supportMessageEntityRepository).save(captor.capture());
        SupportMessageEntity saved = captor.getValue();
        assertEquals(USER_MAIL, saved.getUserMail());
        assertEquals("USER", saved.getSender());
        assertEquals("hello", saved.getMessage());
        verify(supportChatWebSocketHandler).broadcastSupportUpdate(USER_MAIL);
    }

    @Test
    void addUserMessage_shouldPublishPersistedMessageBeforeBroadcast() {
        LocalDateTime createdAt = LocalDateTime.of(2025, 3, 4, 5, 6);
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);
        when(supportMessageEntityRepository.save(any(SupportMessageEntity.class))).thenAnswer(invocation -> {
            SupportMessageEntity saved = invocation.getArgument(0);
            saved.setId(42L);
            saved.setCreatedAt(createdAt);
            return saved;
        });

        userSupportChatService.addUserMessage(USER_MAIL, "  hello  ");

        ArgumentCaptor<SupportMessageEntity> savedMessage = ArgumentCaptor.forClass(SupportMessageEntity.class);
        InOrder sideEffects = inOrder(supportMessageEntityRepository, supportInternalEventService, supportChatWebSocketHandler);
        sideEffects.verify(supportMessageEntityRepository).save(savedMessage.capture());
        sideEffects.verify(supportInternalEventService).publishCustomerMessageCreated(savedMessage.getValue());
        sideEffects.verify(supportChatWebSocketHandler).broadcastSupportUpdate(USER_MAIL);
        assertEquals(42L, savedMessage.getValue().getId());
        assertEquals("hello", savedMessage.getValue().getMessage());
        assertEquals(createdAt, savedMessage.getValue().getCreatedAt());
    }

    @Test
    void addUserMessage_shouldNotPublishOrBroadcastWhenPersistenceFails() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);
        IllegalStateException persistenceFailure = new IllegalStateException("database unavailable");
        when(supportMessageEntityRepository.save(any(SupportMessageEntity.class))).thenThrow(persistenceFailure);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> userSupportChatService.addUserMessage(USER_MAIL, "hello")
        );

        assertSame(persistenceFailure, thrown);
        verifyNoInteractions(supportInternalEventService, supportChatWebSocketHandler);
    }

    @Test
    void addUserMessage_shouldThrowUserNotFound_whenUserMissing() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> userSupportChatService.addUserMessage(USER_MAIL, "msg"));
    }

    @Test
    void addManagementMessage_shouldThrowUserNotFound_whenUserMissing() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> supportModerationService.addManagementMessage(USER_MAIL, "hello"));
        verify(supportMessageEntityRepository, never()).save(any());
        verify(supportChatWebSocketHandler, never()).broadcastSupportUpdate(any());
    }

    @Test
    void addManagementMessage_shouldThrowGeneralException_whenMessageBlank() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);

        assertThrows(GeneralException.class, () -> supportModerationService.addManagementMessage(USER_MAIL, "   "));
        verify(supportMessageEntityRepository, never()).save(any());
        verify(supportChatWebSocketHandler, never()).broadcastSupportUpdate(any());
    }

    @Test
    void addManagementMessage_shouldPersistAndBroadcast() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);
        when(supportMessageEntityRepository.save(any(SupportMessageEntity.class))).thenAnswer(invocation -> {
            SupportMessageEntity saved = invocation.getArgument(0);
            saved.setId(43L);
            return saved;
        });

        supportModerationService.addManagementMessage(USER_MAIL, " hi ", " external-43 ");

        ArgumentCaptor<SupportMessageEntity> captor = ArgumentCaptor.forClass(SupportMessageEntity.class);
        verify(supportMessageEntityRepository).save(captor.capture());
        SupportMessageEntity saved = captor.getValue();
        assertEquals(USER_MAIL, saved.getUserMail());
        assertEquals("MANAGEMENT", saved.getSender());
        assertEquals("hi", saved.getMessage());
        assertEquals("external-43", saved.getExternalMessageId());
        InOrder sideEffects = inOrder(supportMessageEntityRepository, supportChatWebSocketHandler);
        sideEffects.verify(supportMessageEntityRepository).save(saved);
        sideEffects.verify(supportChatWebSocketHandler).broadcastSupportMessageCreated(USER_MAIL, "MANAGEMENT", 43L);
    }

    @Test
    void getMessagesForUserMail_shouldThrowUserNotFound_whenMissing() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> userSupportChatService.getMessagesForUserMail(USER_MAIL));
    }

    @Test
    void getMessagesForUserMail_shouldReturnMappedDtos() {
        LocalDateTime createdAt = LocalDateTime.of(2025, 3, 4, 5, 6);
        LocalDateTime editedAt = LocalDateTime.of(2025, 3, 4, 5, 7);
        SupportMessageEntity message = new SupportMessageEntity(4L, USER_MAIL, "USER", "Hi", createdAt);
        message.setEditedAt(editedAt);
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);
        when(supportMessageEntityRepository.findByUserMailOrderByCreatedAtAscIdAsc(USER_MAIL))
                .thenReturn(Collections.singletonList(message));

        List<SupportMessageDto> result = userSupportChatService.getMessagesForUserMail(USER_MAIL);

        assertEquals(1, result.size());
        assertEquals("USER", result.get(0).getSender());
        assertEquals("Hi", result.get(0).getMessage());
        assertEquals(4L, result.get(0).getId());
        assertEquals(createdAt, result.get(0).getCreatedAt());
        assertEquals(editedAt, result.get(0).getEditedAt());
    }

    @Test
    void getSupportUserMails_shouldReturnDistinctUserMails() {
        when(supportMessageEntityRepository.findDistinctUserMails()).thenReturn(Arrays.asList("a@mail.com", "b@mail.com"));

        List<String> result = supportCustomerDirectoryService.getSupportUserMails();

        assertEquals(2, result.size());
        assertEquals("a@mail.com", result.get(0));
        assertEquals("b@mail.com", result.get(1));
    }

    @Test
    void getBannedUserMails_shouldReturnSortedMails() {
        UserEntity first = new UserEntity();
        first.setUserMail("a@mail.com");
        UserEntity second = new UserEntity();
        second.setUserMail("b@mail.com");

        when(userEntityRepository.findByBannedTrueOrderByUserMailAsc()).thenReturn(Arrays.asList(first, second));

        List<String> result = supportCustomerDirectoryService.getBannedUserMails();

        assertEquals(Arrays.asList("a@mail.com", "b@mail.com"), result);
    }

    @Test
    void closeChat_shouldThrowUserNotFound_whenMissing() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> supportModerationService.closeChat(USER_MAIL));
    }

    @Test
    void closeChat_shouldNoopWhenNoMessages() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);
        when(supportMessageEntityRepository.findByUserMailOrderByCreatedAtAscIdAsc(USER_MAIL))
                .thenReturn(Collections.emptyList());

        supportModerationService.closeChat(USER_MAIL);

        verify(supportMessageEntityRepository, never()).deleteAllInBatch(any());
        verify(supportChatWebSocketHandler, never()).broadcastSupportUpdate(any());
    }

    @Test
    void closeChat_shouldDeleteAndBroadcastWhenMessagesExist() {
        SupportMessageEntity message = new SupportMessageEntity(1L, USER_MAIL, "USER", "Hi", LocalDateTime.now());
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);
        when(supportMessageEntityRepository.findByUserMailOrderByCreatedAtAscIdAsc(USER_MAIL))
                .thenReturn(Collections.singletonList(message));

        supportModerationService.closeChat(USER_MAIL);

        verify(supportMessageEntityRepository).deleteAllInBatch(eq(Collections.singletonList(message)));
        verify(supportChatWebSocketHandler).broadcastSupportUpdate(USER_MAIL);
    }

    @Test
    void banUser_shouldThrowUserNotFound_whenMissing() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> supportModerationService.banUser(USER_MAIL));
    }

    @Test
    void banUser_shouldSetBannedTrueAndBroadcast() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);

        supportModerationService.banUser(USER_MAIL);

        assertTrue(user.getBanned());
        verify(userEntityRepository).save(user);
        verify(supportChatWebSocketHandler).broadcastSupportUpdate(USER_MAIL);
    }

    @Test
    void unbanUser_shouldThrowUserNotFound_whenMissing() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> supportModerationService.unbanUser(USER_MAIL));
    }

    @Test
    void unbanUser_shouldSetBannedFalseAndBroadcast() {
        user.setBanned(true);
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);

        supportModerationService.unbanUser(USER_MAIL);

        assertFalse(user.getBanned());
        verify(userEntityRepository).save(user);
        verify(supportChatWebSocketHandler).broadcastSupportUpdate(USER_MAIL);
    }
}
