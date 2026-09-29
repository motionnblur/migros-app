package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.entity.user.SupportMessageEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.exception.shared.SupportSyncConflictException;
import com.example.MigrosBackend.exception.shared.SupportUserBannedException;
import com.example.MigrosBackend.repository.user.SupportMessageEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.websocket.SupportChatWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportModerationServiceTest {

    @Mock
    private SupportMessageEntityRepository supportMessageEntityRepository;
    @Mock
    private UserEntityRepository userEntityRepository;
    @Mock
    private SupportChatWebSocketHandler supportChatWebSocketHandler;
    @Mock
    private SupportInternalEventService supportInternalEventService;

    private SupportModerationService supportModerationService;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        user.setId(1L);
        user.setUserMail("user@mail.com");
        user.setBanned(false);

        supportModerationService = new SupportModerationService(
                userEntityRepository,
                supportMessageEntityRepository,
                new SupportChatNotificationCoordinator(supportChatWebSocketHandler, supportInternalEventService),
                new SupportChatGuards(userEntityRepository),
                Clock.systemDefaultZone());
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

        supportModerationService.editManagementMessage("user@mail.com", "agent-10", "  new text  ");

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
                () -> supportModerationService.editManagementMessage("user@mail.com", "  ", "updated")
        );

        assertEquals("externalMessageId is required", error.getMessage());
    }

    @Test
    void editManagementMessage_shouldRejectMissingMessage() {
        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);

        GeneralException error = assertThrows(
                GeneralException.class,
                () -> supportModerationService.editManagementMessage("user@mail.com", "agent-10", "  ")
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
                () -> supportModerationService.editManagementMessage("user@mail.com", "agent-10", "updated")
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
                () -> supportModerationService.editManagementMessage("user@mail.com", "missing-id", "updated")
        );

        assertEquals("Editable support message not found", error.getMessage());
    }

    @Test
    void deleteManagementMessage_shouldDeleteAndBroadcast_whenValid() {
        SupportMessageEntity entity = new SupportMessageEntity();
        entity.setId(10L);
        entity.setUserMail("user@mail.com");
        entity.setSender("MANAGEMENT");
        entity.setExternalMessageId("agent-10");

        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);
        when(supportMessageEntityRepository.findByUserMailAndExternalMessageId("user@mail.com", "agent-10"))
                .thenReturn(Optional.of(entity));

        supportModerationService.deleteManagementMessage("user@mail.com", "agent-10");

        verify(supportMessageEntityRepository).delete(entity);
        verify(supportChatWebSocketHandler).broadcastSupportUpdate("user@mail.com");
    }

    @Test
    void deleteManagementMessage_shouldRejectMissingExternalMessageId() {
        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);

        GeneralException error = assertThrows(
                GeneralException.class,
                () -> supportModerationService.deleteManagementMessage("user@mail.com", "   ")
        );

        assertEquals("externalMessageId is required", error.getMessage());
    }

    @Test
    void deleteManagementMessage_shouldRejectNonManagementMessages() {
        SupportMessageEntity entity = new SupportMessageEntity();
        entity.setUserMail("user@mail.com");
        entity.setSender("USER");
        entity.setExternalMessageId("agent-10");

        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);
        when(supportMessageEntityRepository.findByUserMailAndExternalMessageId("user@mail.com", "agent-10"))
                .thenReturn(Optional.of(entity));

        GeneralException error = assertThrows(
                GeneralException.class,
                () -> supportModerationService.deleteManagementMessage("user@mail.com", "agent-10")
        );

        assertEquals("Only management messages can be deleted", error.getMessage());
    }

    @Test
    void deleteManagementMessage_shouldThrowWhenMessageNotFound() {
        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);
        when(supportMessageEntityRepository.findByUserMailAndExternalMessageId("user@mail.com", "missing-id"))
                .thenReturn(Optional.empty());

        GeneralException error = assertThrows(
                GeneralException.class,
                () -> supportModerationService.deleteManagementMessage("user@mail.com", "missing-id")
        );

        assertEquals("Deletable support message not found", error.getMessage());
    }

    @Test
    void editMessageForAdmin_shouldEditUserMessageAndPublishSync() {
        SupportMessageEntity entity = new SupportMessageEntity();
        entity.setId(22L);
        entity.setUserMail("user@mail.com");
        entity.setSender("USER");
        entity.setMessage("old");

        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);
        when(supportMessageEntityRepository.findByIdAndUserMail(22L, "user@mail.com"))
                .thenReturn(Optional.of(entity));

        supportModerationService.editMessageForAdmin("user@mail.com", 22L, "  updated user text ");

        assertEquals("updated user text", entity.getMessage());
        assertNotNull(entity.getEditedAt());
        InOrder notificationOrder = inOrder(
                supportMessageEntityRepository,
                supportInternalEventService,
                supportChatWebSocketHandler
        );
        notificationOrder.verify(supportMessageEntityRepository).save(entity);
        notificationOrder.verify(supportInternalEventService)
                .publishSupportMessageEdited("user@mail.com", "22", "updated user text");
        notificationOrder.verify(supportChatWebSocketHandler).broadcastSupportUpdate("user@mail.com");
    }

    @Test
    void editMessageForAdmin_shouldEditManagementMessageWithExternalIdAndPublishSync() {
        SupportMessageEntity entity = new SupportMessageEntity();
        entity.setId(33L);
        entity.setUserMail("user@mail.com");
        entity.setSender("MANAGEMENT");
        entity.setMessage("old");
        entity.setExternalMessageId("agent-33");

        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);
        when(supportMessageEntityRepository.findByIdAndUserMail(33L, "user@mail.com"))
                .thenReturn(Optional.of(entity));

        supportModerationService.editMessageForAdmin("user@mail.com", 33L, "new text");

        verify(supportInternalEventService).publishSupportMessageEdited("user@mail.com", "agent-33", "new text");
    }

    @Test
    void editMessageForAdmin_shouldRejectLegacyManagementWithoutExternalId() {
        SupportMessageEntity entity = new SupportMessageEntity();
        entity.setId(34L);
        entity.setUserMail("user@mail.com");
        entity.setSender("MANAGEMENT");
        entity.setExternalMessageId(null);

        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);
        when(supportMessageEntityRepository.findByIdAndUserMail(34L, "user@mail.com"))
                .thenReturn(Optional.of(entity));

        assertThrows(
                SupportSyncConflictException.class,
                () -> supportModerationService.editMessageForAdmin("user@mail.com", 34L, "updated")
        );
    }

    @Test
    void editMessageForAdmin_shouldRejectMessagesOwnedByAnotherUser() {
        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);
        when(supportMessageEntityRepository.findByIdAndUserMail(99L, "user@mail.com"))
                .thenReturn(Optional.empty());

        GeneralException error = assertThrows(
                GeneralException.class,
                () -> supportModerationService.editMessageForAdmin("user@mail.com", 99L, "hijacked")
        );

        assertEquals("Support message not found", error.getMessage());
        verify(supportMessageEntityRepository, never()).save(any());
        verifyNoInteractions(supportInternalEventService);
    }

    @Test
    void deleteMessageForAdmin_shouldDeleteUserMessageAndPublishSync() {
        SupportMessageEntity entity = new SupportMessageEntity();
        entity.setId(41L);
        entity.setUserMail("user@mail.com");
        entity.setSender("USER");

        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);
        when(supportMessageEntityRepository.findByIdAndUserMail(41L, "user@mail.com"))
                .thenReturn(Optional.of(entity));

        supportModerationService.deleteMessageForAdmin("user@mail.com", 41L);

        InOrder notificationOrder = inOrder(
                supportMessageEntityRepository,
                supportInternalEventService,
                supportChatWebSocketHandler
        );
        notificationOrder.verify(supportMessageEntityRepository).delete(entity);
        notificationOrder.verify(supportInternalEventService)
                .publishSupportMessageDeleted("user@mail.com", "41");
        notificationOrder.verify(supportChatWebSocketHandler).broadcastSupportUpdate("user@mail.com");
    }

    @Test
    void deleteMessageForAdmin_shouldRejectLegacyManagementWithoutExternalId() {
        SupportMessageEntity entity = new SupportMessageEntity();
        entity.setId(42L);
        entity.setUserMail("user@mail.com");
        entity.setSender("MANAGEMENT");

        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);
        when(supportMessageEntityRepository.findByIdAndUserMail(42L, "user@mail.com"))
                .thenReturn(Optional.of(entity));

        assertThrows(
                SupportSyncConflictException.class,
                () -> supportModerationService.deleteMessageForAdmin("user@mail.com", 42L)
        );
    }

    @Test
    void deleteMessageForAdmin_shouldRejectMessagesOwnedByAnotherUser() {
        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);
        when(supportMessageEntityRepository.findByIdAndUserMail(98L, "user@mail.com"))
                .thenReturn(Optional.empty());

        GeneralException error = assertThrows(
                GeneralException.class,
                () -> supportModerationService.deleteMessageForAdmin("user@mail.com", 98L)
        );

        assertEquals("Support message not found", error.getMessage());
        verify(supportMessageEntityRepository, never()).delete(any());
        verifyNoInteractions(supportInternalEventService);
    }

    /**
     * The ban is written as the single column it owns. A whole-entity save would
     * also put back the cart, the profile and the password hash as they were
     * read, silently undoing work the customer did at the same moment.
     */
    @Test
    void banUser_shouldWriteOnlyTheBanFlagAndBroadcast() {
        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);
        when(userEntityRepository.updateBannedByUserMail("user@mail.com", true)).thenReturn(1);

        supportModerationService.banUser("user@mail.com");

        verify(userEntityRepository).updateBannedByUserMail("user@mail.com", true);
        verify(userEntityRepository, never()).save(any());
        verify(supportChatWebSocketHandler).broadcastSupportUpdate("user@mail.com");
    }

    @Test
    void unbanUser_shouldWriteOnlyTheBanFlagAndBroadcast() {
        user.setBanned(true);
        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);
        when(userEntityRepository.updateBannedByUserMail("user@mail.com", false)).thenReturn(1);

        supportModerationService.unbanUser("user@mail.com");

        verify(userEntityRepository).updateBannedByUserMail("user@mail.com", false);
        verify(userEntityRepository, never()).save(any());
        verify(supportChatWebSocketHandler).broadcastSupportUpdate("user@mail.com");
    }

    /** The guard already rejects an unknown mailbox before any write is issued. */
    @Test
    void banUser_shouldThrowUserNotFound_whenMissing() {
        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> supportModerationService.banUser("user@mail.com"));

        verify(userEntityRepository, never()).updateBannedByUserMail(anyString(), anyBoolean());
        verifyNoInteractions(supportChatWebSocketHandler);
    }

    @Test
    void unbanUser_shouldThrowUserNotFound_whenMissing() {
        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> supportModerationService.unbanUser("user@mail.com"));

        verify(userEntityRepository, never()).updateBannedByUserMail(anyString(), anyBoolean());
        verifyNoInteractions(supportChatWebSocketHandler);
    }

    /**
     * The row can disappear between the guard's read and the update. That is
     * reported as the same not-found error instead of a silent success with no
     * ban applied.
     */
    @Test
    void banUser_shouldReportNotFound_whenTheUpdateAffectsNoRow() {
        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);
        when(userEntityRepository.updateBannedByUserMail("user@mail.com", true)).thenReturn(0);

        assertThrows(UserNotFoundException.class, () -> supportModerationService.banUser("user@mail.com"));

        verifyNoInteractions(supportChatWebSocketHandler);
    }

    /** The pre-existing send-time ban check is unchanged by the field-specific write. */
    @Test
    void addManagementMessage_shouldStillRefuseABannedCustomer() {
        user.setBanned(true);
        when(userEntityRepository.findByUserMail("user@mail.com")).thenReturn(user);

        SupportUserBannedException failure = assertThrows(
                SupportUserBannedException.class,
                () -> supportModerationService.addManagementMessage("user@mail.com", "hello"));

        assertEquals("User is banned. Sending messages is disabled.", failure.getMessage());
        verify(supportMessageEntityRepository, never()).save(any());
    }
}
