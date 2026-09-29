package com.example.MigrosBackend.controller.user.support;

import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.dto.user.support.SupportMessageDto;
import com.example.MigrosBackend.dto.user.support.SupportSendMessageDto;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.support.UserSupportChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserSupportController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserSupportControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserSupportChatService userSupportChatService;

    @MockBean
    private AuthTokenResolver authTokenResolver;

    @MockBean
    private AdminEntityRepository adminEntityRepository;

    @MockBean
    private TokenService tokenService;

    @Test
    void getSupportMessages_shouldReturnList() throws Exception {
        SupportMessageDto dto = new SupportMessageDto();
        dto.setMessage("Hello");

        when(authTokenResolver.requireAuthenticatedUserMail()).thenReturn("user@example.com");
        when(userSupportChatService.getMessagesForUser("user@example.com")).thenReturn(List.of(dto));

        mockMvc.perform(get("/user/support/messages")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message").value("Hello"));
    }

    @Test
    void sendSupportMessage_shouldReturnOk() throws Exception {
        SupportSendMessageDto dto = new SupportSendMessageDto();
        dto.setMessage("Need help");

        when(authTokenResolver.requireAuthenticatedUserMail()).thenReturn("user@example.com");
        doNothing().when(userSupportChatService).addUserMessage("user@example.com", "Need help");

        mockMvc.perform(post("/user/support/send")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());

        verify(userSupportChatService).addUserMessage("user@example.com", "Need help");
    }

    @Test
    void sendSupportMessage_shouldReturnTypedBadRequest_whenMessageBlank() throws Exception {
        SupportSendMessageDto dto = new SupportSendMessageDto();
        dto.setMessage("   ");

        mockMvc.perform(post("/user/support/send")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("message"));
    }

    @Test
    void sendSupportMessage_shouldReturnTypedBadRequest_whenMessageTooLong() throws Exception {
        SupportSendMessageDto dto = new SupportSendMessageDto();
        dto.setMessage("x".repeat(2001));

        mockMvc.perform(post("/user/support/send")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("message"));
    }
}
