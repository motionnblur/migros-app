package com.example.MigrosBackend.controller.internal;

import com.example.MigrosBackend.dto.support.InternalSupportAgentMessageDto;
import com.example.MigrosBackend.dto.support.InternalSupportDeleteAgentMessageDto;
import com.example.MigrosBackend.dto.support.InternalSupportEditAgentMessageDto;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.support.SupportCustomerDirectoryService;
import com.example.MigrosBackend.service.support.SupportModerationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-layer tests with filters disabled: the {@code x-internal-key} check
 * lives in {@code InternalApiKeyFilter} and is covered by the filter/security
 * tests. These tests verify request/response translation only.
 */
@WebMvcTest(InternalSupportController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "support.internal.key=test-internal-key")
class InternalSupportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SupportCustomerDirectoryService supportCustomerDirectoryService;

    @MockBean
    private SupportModerationService supportModerationService;

    @MockBean
    private AdminEntityRepository adminEntityRepository;

    @MockBean
    private TokenService tokenService;

    @Test
    void receiveAgentMessage_shouldReturnAccepted_whenPayloadValid() throws Exception {
        InternalSupportAgentMessageDto dto = new InternalSupportAgentMessageDto(
                "user@test.com",
                "Hello",
                "agent-123"
        );

        doNothing().when(supportModerationService)
                .addManagementMessage("user@test.com", "Hello", "agent-123");

        mockMvc.perform(post("/internal/support/agent-message")
                        .header("x-internal-key", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isAccepted());
    }

    @Test
    void editAgentMessage_shouldReturnAccepted_whenPayloadValid() throws Exception {
        InternalSupportEditAgentMessageDto dto = new InternalSupportEditAgentMessageDto(
                "user@test.com",
                "agent-123",
                "Updated message"
        );

        doNothing().when(supportModerationService)
                .editManagementMessage("user@test.com", "agent-123", "Updated message");

        mockMvc.perform(post("/internal/support/edit-agent-message")
                        .header("x-internal-key", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isAccepted());
    }

    @Test
    void deleteAgentMessage_shouldReturnAccepted_whenPayloadValid() throws Exception {
        InternalSupportDeleteAgentMessageDto dto = new InternalSupportDeleteAgentMessageDto(
                "user@test.com",
                "agent-123"
        );

        doNothing().when(supportModerationService)
                .deleteManagementMessage("user@test.com", "agent-123");

        mockMvc.perform(post("/internal/support/delete-agent-message")
                        .header("x-internal-key", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isAccepted());
    }

    @Test
    void customers_shouldReturnOk_whenServiceResponds() throws Exception {
        mockMvc.perform(get("/internal/support/customers")
                        .header("x-internal-key", "test-internal-key"))
                .andExpect(status().isOk());
    }

    @Test
    void receiveAgentMessage_shouldReturnTypedBadRequest_whenMailBlank() throws Exception {
        InternalSupportAgentMessageDto dto = new InternalSupportAgentMessageDto(
                "   ",
                "Hello",
                "agent-123"
        );

        mockMvc.perform(post("/internal/support/agent-message")
                        .header("x-internal-key", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("userMail"));
    }

    @Test
    void editAgentMessage_shouldReturnTypedBadRequest_whenExternalIdBlank() throws Exception {
        InternalSupportEditAgentMessageDto dto = new InternalSupportEditAgentMessageDto(
                "user@test.com",
                "  ",
                "Updated message"
        );

        mockMvc.perform(post("/internal/support/edit-agent-message")
                        .header("x-internal-key", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("externalMessageId"));
    }
}
