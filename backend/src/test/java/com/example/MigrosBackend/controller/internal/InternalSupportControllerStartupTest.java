package com.example.MigrosBackend.controller.internal;

import com.example.MigrosBackend.service.support.SupportChatService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * The internal-key startup guard moved to {@code InternalApiKeyFilter}; the
 * controller no longer owns the key and must be constructible with only its
 * service dependency.
 */
class InternalSupportControllerStartupTest {

    private final SupportChatService supportChatService = mock(SupportChatService.class);

    @Test
    void constructorAcceptsServiceWithoutKeyConfiguration() {
        assertThatCode(() -> new InternalSupportController(supportChatService))
                .doesNotThrowAnyException();
    }
}
