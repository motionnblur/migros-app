package com.example.MigrosBackend.controller.admin.panel;

import com.example.MigrosBackend.dto.admin.panel.SupportAdminEditMessageDto;
import com.example.MigrosBackend.dto.admin.panel.SupportReplyDto;
import com.example.MigrosBackend.dto.user.support.SupportMessageDto;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.support.SupportChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminSupportController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminSupportControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SupportChatService supportChatService;

    @MockBean
    private TokenService tokenService;

    @Test
    void getSupportUsers_shouldReturnList() throws Exception {
        List<String> users = List.of("user1@test.com", "user2@test.com");
        when(supportChatService.getSupportUserMails()).thenReturn(users);

        mockMvc.perform(get("/admin/panel/support/users"))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(users)));
    }

    @Test
    void getBannedUsers_shouldReturnList() throws Exception {
        List<String> users = List.of("banned@test.com");
        when(supportChatService.getBannedUserMails()).thenReturn(users);

        mockMvc.perform(get("/admin/panel/support/banned-users"))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(users)));
    }

    @Test
    void getSupportMessages_shouldReturnList() throws Exception {
        SupportMessageDto dto = new SupportMessageDto();
        dto.setMessage("Hello");
        when(supportChatService.getMessagesForUserMail("user@test.com")).thenReturn(List.of(dto));

        mockMvc.perform(get("/admin/panel/support/messages")
                        .param("userMail", "user@test.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message").value("Hello"));
    }

    @Test
    void sendSupportReply_shouldReturnOk() throws Exception {
        SupportReplyDto reply = new SupportReplyDto();
        reply.setUserMail("user@test.com");
        reply.setMessage("We will help");

        doNothing().when(supportChatService).addManagementMessage("user@test.com", "We will help");

        mockMvc.perform(post("/admin/panel/support/reply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reply)))
                .andExpect(status().isOk());
    }

    @Test
    void editSupportMessage_shouldReturnOk() throws Exception {
        SupportAdminEditMessageDto dto = new SupportAdminEditMessageDto("user@test.com", "updated");

        doNothing().when(supportChatService).editMessageForAdmin("user@test.com", 10L, "updated");

        mockMvc.perform(patch("/admin/panel/support/messages/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());
    }

    @Test
    void deleteSupportMessage_shouldReturnOk() throws Exception {
        doNothing().when(supportChatService).deleteMessageForAdmin("user@test.com", 10L);

        mockMvc.perform(delete("/admin/panel/support/messages/10")
                        .param("userMail", "user@test.com"))
                .andExpect(status().isOk());
    }

    @Test
    void closeSupportChatDelete_shouldReturnOk() throws Exception {
        doNothing().when(supportChatService).closeChat("user@test.com");

        mockMvc.perform(delete("/admin/panel/support/close")
                        .param("userMail", "user@test.com"))
                .andExpect(status().isOk());
    }

    @Test
    void closeSupportChatPost_shouldReturnOk() throws Exception {
        doNothing().when(supportChatService).closeChat("user@test.com");

        mockMvc.perform(post("/admin/panel/support/close")
                        .param("userMail", "user@test.com"))
                .andExpect(status().isOk());
    }

    @Test
    void banSupportUser_shouldReturnOk() throws Exception {
        doNothing().when(supportChatService).banUser("user@test.com");

        mockMvc.perform(post("/admin/panel/support/ban")
                        .param("userMail", "user@test.com"))
                .andExpect(status().isOk());
    }

    @Test
    void unbanSupportUser_shouldReturnOk() throws Exception {
        doNothing().when(supportChatService).unbanUser("user@test.com");

        mockMvc.perform(post("/admin/panel/support/unban")
                        .param("userMail", "user@test.com"))
                .andExpect(status().isOk());
    }
}
