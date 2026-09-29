package com.example.MigrosBackend.controller.internal;

import com.example.MigrosBackend.service.support.SupportCustomerDirectoryService;
import com.example.MigrosBackend.service.support.SupportModerationService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * The internal-key startup guard moved to {@code InternalApiKeyFilter}; the
 * controller no longer owns the key and must be constructible with only its
 * service dependencies.
 */
class InternalSupportControllerStartupTest {

    private final SupportCustomerDirectoryService supportCustomerDirectoryService =
            mock(SupportCustomerDirectoryService.class);
    private final SupportModerationService supportModerationService =
            mock(SupportModerationService.class);

    @Test
    void constructorAcceptsServiceWithoutKeyConfiguration() {
        assertThatCode(() -> new InternalSupportController(supportCustomerDirectoryService, supportModerationService))
                .doesNotThrowAnyException();
    }
}
