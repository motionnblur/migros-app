package com.example.MigrosBackend.controller.internal;

import com.example.MigrosBackend.dto.support.InternalSupportAgentMessageDto;
import com.example.MigrosBackend.dto.support.InternalSupportEditAgentMessageDto;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.support.SupportChatService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalSupportController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "support.internal.key=test-internal-key")
class InternalSupportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SupportChatService supportChatService;

    @MockBean
    private TokenService tokenService;

    @Test
    void receiveAgentMessage_shouldReturnAccepted_whenPayloadValid() throws Exception {
        InternalSupportAgentMessageDto dto = new InternalSupportAgentMessageDto(
                "user@test.com",
                "Hello",
                "agent-123"
        );

        doNothing().when(supportChatService)
                .addManagementMessage("user@test.com", "Hello", "agent-123");

        mockMvc.perform(post("/internal/support/agent-message")
                        .header("x-internal-key", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isAccepted());
    }

    @Test
    void receiveAgentMessage_shouldReturnUnauthorized_whenKeyInvalid() throws Exception {
        InternalSupportAgentMessageDto dto = new InternalSupportAgentMessageDto(
                "user@test.com",
                "Hello",
                "agent-123"
        );

        mockMvc.perform(post("/internal/support/agent-message")
                        .header("x-internal-key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void editAgentMessage_shouldReturnAccepted_whenPayloadValid() throws Exception {
        InternalSupportEditAgentMessageDto dto = new InternalSupportEditAgentMessageDto(
                "user@test.com",
                "agent-123",
                "Updated message"
        );

        doNothing().when(supportChatService)
                .editManagementMessage("user@test.com", "agent-123", "Updated message");

        mockMvc.perform(post("/internal/support/edit-agent-message")
                        .header("x-internal-key", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isAccepted());
    }
}
