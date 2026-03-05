package com.example.MigrosBackend.controller.user.sign;

import com.example.MigrosBackend.dto.user.sign.UserSignDto;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.sign.UserSignupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserSignController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserSignControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserSignupService userSignupService;

    @MockBean
    private TokenService tokenService;

    private UserSignDto userSignDto;

    @BeforeEach
    void setup() {
        userSignDto = new UserSignDto();
        userSignDto.setUserMail("test@example.com");
        userSignDto.setUserPassword("password123");
    }

    @Test
    void signup_shouldReturnOk() throws Exception {
        doNothing().when(userSignupService).signup(any(UserSignDto.class));

        mockMvc.perform(post("/user/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userSignDto)))
                .andExpect(status().isOk());
    }

    @Test
    void confirm_shouldReturnOkMessage() throws Exception {
        doNothing().when(userSignupService).confirm(anyString());

        mockMvc.perform(get("/user/signup/confirm")
                        .param("token", "sample-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("Your account has been created successfully, you can close this page now."));
    }

    @Test
    void login_shouldReturnToken() throws Exception {
        when(userSignupService.login(any(UserSignDto.class))).thenReturn("mock-jwt-token");

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userSignDto)))
                .andExpect(status().isOk())
                .andExpect(content().string("mock-jwt-token"));
    }
}