package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.entity.user.SupportMessageEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.user.SupportMessageEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.websocket.SupportChatWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportChatServiceEditMessageTest {

    @Mock
    private SupportMessageEntityRepository supportMessageEntityRepository;
    @Mock
    private UserEntityRepository userEntityRepository;
    @Mock
    private TokenService tokenService;
    @Mock
    private SupportChatWebSocketHandler supportChatWebSocketHandler;
    @Mock
    private SupportInternalEventService supportInternalEventService;

    @InjectMocks
    private SupportChatService supportChatService;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        user.setId(1L);
        user.setUserMail("user@mail.com");
        user.setBanned(false);
    }

    @Test
    void editManagementMessage_shouldUpdateTextAndEditedAt_whenValid() {
        SupportMessageEntity entity = new SupportMessageEntity();
        entity.setId(10L);
        entity.setUserMail("user@mail.com");
        entity.setSender("MANAGEMENT");
        entity.setMessage("old text");
        entity.setExternalMessageId("agent-10");
        entity.setCreatedAt(LocalDateTime.now().minusMinutes(1));

        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);
        when(supportMessageEntityRepository.findByUserMailAndExternalMessageId("user@mail.com", "agent-10"))
                .thenReturn(Optional.of(entity));

        supportChatService.editManagementMessage("user@mail.com", "agent-10", "  new text  ");

        assertEquals("new text", entity.getMessage());
        assertNotNull(entity.getEditedAt());
        verify(supportMessageEntityRepository).save(entity);
        verify(supportChatWebSocketHandler).broadcastSupportUpdate("user@mail.com");
    }

    @Test
    void editManagementMessage_shouldRejectMissingExternalMessageId() {
        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);

        GeneralException error = assertThrows(
                GeneralException.class,
                () -> supportChatService.editManagementMessage("user@mail.com", "  ", "updated")
        );

        assertEquals("externalMessageId is required", error.getMessage());
    }

    @Test
    void editManagementMessage_shouldRejectMissingMessage() {
        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);

        GeneralException error = assertThrows(
                GeneralException.class,
                () -> supportChatService.editManagementMessage("user@mail.com", "agent-10", "  ")
        );

        assertEquals("Message cannot be empty", error.getMessage());
    }

    @Test
    void editManagementMessage_shouldRejectNonManagementMessages() {
        SupportMessageEntity entity = new SupportMessageEntity();
        entity.setUserMail("user@mail.com");
        entity.setSender("USER");
        entity.setExternalMessageId("agent-10");

        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);
        when(supportMessageEntityRepository.findByUserMailAndExternalMessageId("user@mail.com", "agent-10"))
                .thenReturn(Optional.of(entity));

        GeneralException error = assertThrows(
                GeneralException.class,
                () -> supportChatService.editManagementMessage("user@mail.com", "agent-10", "updated")
        );

        assertEquals("Only management messages can be edited", error.getMessage());
    }

    @Test
    void editManagementMessage_shouldThrowWhenMessageNotFound() {
        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);
        when(supportMessageEntityRepository.findByUserMailAndExternalMessageId("user@mail.com", "missing-id"))
                .thenReturn(Optional.empty());

        GeneralException error = assertThrows(
                GeneralException.class,
                () -> supportChatService.editManagementMessage("user@mail.com", "missing-id", "updated")
        );

        assertEquals("Editable support message not found", error.getMessage());
    }
}
