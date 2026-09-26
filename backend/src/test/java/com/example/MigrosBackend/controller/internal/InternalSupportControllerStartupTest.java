package com.example.MigrosBackend.controller.internal;

import com.example.MigrosBackend.service.support.SupportChatService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class InternalSupportControllerStartupTest {

    private final SupportChatService supportChatService = mock(SupportChatService.class);

    @Test
    void constructorFailsWhenKeyIsNull() {
        assertThatThrownBy(() -> new InternalSupportController(supportChatService, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructorFailsWhenKeyIsBlank() {
        assertThatThrownBy(() -> new InternalSupportController(supportChatService, "   "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructorAcceptsNonBlankKey() {
        assertThatCode(() -> new InternalSupportController(supportChatService, "internal-key"))
                .doesNotThrowAnyException();
    }
}
