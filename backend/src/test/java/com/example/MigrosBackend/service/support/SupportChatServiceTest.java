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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupportChatServiceTest {
    private static final String TOKEN = "token";
    private static final String USER_MAIL = "user@mail.com";

    @Mock
    private SupportMessageEntityRepository supportMessageEntityRepository;
    @Mock
    private UserEntityRepository userEntityRepository;
    @Mock
    private TokenService tokenService;
    @Mock
    private SupportChatWebSocketHandler supportChatWebSocketHandler;

    @InjectMocks
    private SupportChatService supportChatService;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        user.setId(1L);
        user.setUserMail(USER_MAIL);
        user.setBanned(false);
    }

    @Test
    void getMessagesForUser_shouldReturnMappedDtos_whenTokenValidAndNotBanned() {
        SupportMessageEntity first = new SupportMessageEntity(1L, USER_MAIL, "USER", "Hello", LocalDateTime.now().minusMinutes(1));
        SupportMessageEntity second = new SupportMessageEntity(2L, USER_MAIL, "MANAGEMENT", "Hi there", LocalDateTime.now());

        when(tokenService.extractUsername(TOKEN)).thenReturn(USER_MAIL);
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);
        when(tokenService.validateToken(TOKEN, USER_MAIL)).thenReturn(true);
        when(supportMessageEntityRepository.findByUserMailOrderByCreatedAtAscIdAsc(USER_MAIL))
                .thenReturn(Arrays.asList(first, second));

        List<SupportMessageDto> result = supportChatService.getMessagesForUser(TOKEN);

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getId());
        assertEquals("USER", result.get(0).getSender());
        assertEquals("Hello", result.get(0).getMessage());
        assertEquals(2L, result.get(1).getId());
        assertEquals("MANAGEMENT", result.get(1).getSender());
    }

    @Test
    void getMessagesForUser_shouldThrowInvalidToken_whenTokenInvalid() {
        when(tokenService.extractUsername(TOKEN)).thenReturn(USER_MAIL);
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);
        when(tokenService.validateToken(TOKEN, USER_MAIL)).thenReturn(false);

        assertThrows(InvalidTokenException.class, () -> supportChatService.getMessagesForUser(TOKEN));
    }

    @Test
    void getMessagesForUser_shouldThrowGeneralException_whenUserBanned() {
        user.setBanned(true);
        when(tokenService.extractUsername(TOKEN)).thenReturn(USER_MAIL);
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);
        when(tokenService.validateToken(TOKEN, USER_MAIL)).thenReturn(true);

        GeneralException ex = assertThrows(GeneralException.class, () -> supportChatService.getMessagesForUser(TOKEN));
        assertEquals("You are banned from live support.", ex.getMessage());
    }

    @Test
    void addUserMessage_shouldThrowGeneralException_whenMessageBlank() {
        when(tokenService.extractUsername(TOKEN)).thenReturn(USER_MAIL);
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);
        when(tokenService.validateToken(TOKEN, USER_MAIL)).thenReturn(true);

        assertThrows(GeneralException.class, () -> supportChatService.addUserMessage(TOKEN, "   "));
        verify(supportMessageEntityRepository, never()).save(any());
        verify(supportChatWebSocketHandler, never()).broadcastSupportUpdate(any());
    }

    @Test
    void addUserMessage_shouldTrimAndPersist_andBroadcast() {
        when(tokenService.extractUsername(TOKEN)).thenReturn(USER_MAIL);
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);
        when(tokenService.validateToken(TOKEN, USER_MAIL)).thenReturn(true);

        supportChatService.addUserMessage(TOKEN, "  hello  ");

        ArgumentCaptor<SupportMessageEntity> captor = ArgumentCaptor.forClass(SupportMessageEntity.class);
        verify(supportMessageEntityRepository).save(captor.capture());
        SupportMessageEntity saved = captor.getValue();
        assertEquals(USER_MAIL, saved.getUserMail());
        assertEquals("USER", saved.getSender());
        assertEquals("hello", saved.getMessage());
        verify(supportChatWebSocketHandler).broadcastSupportUpdate(USER_MAIL);
    }

    @Test
    void addUserMessage_shouldThrowUserNotFound_whenTokenUserMissing() {
        when(tokenService.extractUsername(TOKEN)).thenReturn(USER_MAIL);
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> supportChatService.addUserMessage(TOKEN, "msg"));
    }

    @Test
    void addManagementMessage_shouldThrowUserNotFound_whenUserMissing() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> supportChatService.addManagementMessage(USER_MAIL, "hello"));
        verify(supportMessageEntityRepository, never()).save(any());
        verify(supportChatWebSocketHandler, never()).broadcastSupportUpdate(any());
    }

    @Test
    void addManagementMessage_shouldThrowGeneralException_whenMessageBlank() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);

        assertThrows(GeneralException.class, () -> supportChatService.addManagementMessage(USER_MAIL, "   "));
        verify(supportMessageEntityRepository, never()).save(any());
        verify(supportChatWebSocketHandler, never()).broadcastSupportUpdate(any());
    }

    @Test
    void addManagementMessage_shouldPersistAndBroadcast() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);

        supportChatService.addManagementMessage(USER_MAIL, " hi ");

        ArgumentCaptor<SupportMessageEntity> captor = ArgumentCaptor.forClass(SupportMessageEntity.class);
        verify(supportMessageEntityRepository).save(captor.capture());
        SupportMessageEntity saved = captor.getValue();
        assertEquals(USER_MAIL, saved.getUserMail());
        assertEquals("MANAGEMENT", saved.getSender());
        assertEquals("hi", saved.getMessage());
        verify(supportChatWebSocketHandler).broadcastSupportUpdate(USER_MAIL);
    }

    @Test
    void getMessagesForUserMail_shouldThrowUserNotFound_whenMissing() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> supportChatService.getMessagesForUserMail(USER_MAIL));
    }

    @Test
    void getMessagesForUserMail_shouldReturnMappedDtos() {
        SupportMessageEntity message = new SupportMessageEntity(4L, USER_MAIL, "USER", "Hi", LocalDateTime.now());
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);
        when(supportMessageEntityRepository.findByUserMailOrderByCreatedAtAscIdAsc(USER_MAIL))
                .thenReturn(Collections.singletonList(message));

        List<SupportMessageDto> result = supportChatService.getMessagesForUserMail(USER_MAIL);

        assertEquals(1, result.size());
        assertEquals("USER", result.get(0).getSender());
        assertEquals("Hi", result.get(0).getMessage());
    }

    @Test
    void getSupportUserMails_shouldReturnDistinctUserMails() {
        when(supportMessageEntityRepository.findDistinctUserMails()).thenReturn(Arrays.asList("a@mail.com", "b@mail.com"));

        List<String> result = supportChatService.getSupportUserMails();

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

        List<String> result = supportChatService.getBannedUserMails();

        assertEquals(Arrays.asList("a@mail.com", "b@mail.com"), result);
    }

    @Test
    void closeChat_shouldThrowUserNotFound_whenMissing() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> supportChatService.closeChat(USER_MAIL));
    }

    @Test
    void closeChat_shouldNoopWhenNoMessages() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);
        when(supportMessageEntityRepository.findByUserMailOrderByCreatedAtAscIdAsc(USER_MAIL))
                .thenReturn(Collections.emptyList());

        supportChatService.closeChat(USER_MAIL);

        verify(supportMessageEntityRepository, never()).deleteAllInBatch(any());
        verify(supportChatWebSocketHandler, never()).broadcastSupportUpdate(any());
    }

    @Test
    void closeChat_shouldDeleteAndBroadcastWhenMessagesExist() {
        SupportMessageEntity message = new SupportMessageEntity(1L, USER_MAIL, "USER", "Hi", LocalDateTime.now());
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);
        when(supportMessageEntityRepository.findByUserMailOrderByCreatedAtAscIdAsc(USER_MAIL))
                .thenReturn(Collections.singletonList(message));

        supportChatService.closeChat(USER_MAIL);

        verify(supportMessageEntityRepository).deleteAllInBatch(eq(Collections.singletonList(message)));
        verify(supportChatWebSocketHandler).broadcastSupportUpdate(USER_MAIL);
    }

    @Test
    void banUser_shouldThrowUserNotFound_whenMissing() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> supportChatService.banUser(USER_MAIL));
    }

    @Test
    void banUser_shouldSetBannedTrueAndBroadcast() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);

        supportChatService.banUser(USER_MAIL);

        assertTrue(user.getBanned());
        verify(userEntityRepository).save(user);
        verify(supportChatWebSocketHandler).broadcastSupportUpdate(USER_MAIL);
    }

    @Test
    void unbanUser_shouldThrowUserNotFound_whenMissing() {
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> supportChatService.unbanUser(USER_MAIL));
    }

    @Test
    void unbanUser_shouldSetBannedFalseAndBroadcast() {
        user.setBanned(true);
        when(userEntityRepository.findByUserMail(USER_MAIL)).thenReturn(user);

        supportChatService.unbanUser(USER_MAIL);

        assertFalse(user.getBanned());
        verify(userEntityRepository).save(user);
        verify(supportChatWebSocketHandler).broadcastSupportUpdate(USER_MAIL);
    }
}
