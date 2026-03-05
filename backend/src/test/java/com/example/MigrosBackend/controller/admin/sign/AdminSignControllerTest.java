package com.example.MigrosBackend.controller.admin.sign;

import com.example.MigrosBackend.dto.admin.sign.AdminSignDto;
import com.example.MigrosBackend.exception.admin.AdminNotFoundException;
import com.example.MigrosBackend.exception.shared.WrongPasswordException;
import com.example.MigrosBackend.service.admin.sign.AdminSignupService;
import com.example.MigrosBackend.service.global.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminSignController.class) // Focuses only on the Web layer
@AutoConfigureMockMvc(addFilters = false) // Disables Spring Security filters for this unit test
class AdminSignControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminSignupService adminSignupService;

    @MockBean
    private TokenService tokenService;

    @Autowired
    private ObjectMapper objectMapper; // Used to convert DTO to JSON

    private AdminSignDto dto;

    @BeforeEach
    void setup() {
        dto = new AdminSignDto();
        dto.setAdminName("admin");
        dto.setAdminPassword("password");
    }

    @Test
    void login_shouldReturnToken_whenCredentialsValid() throws Exception {

        when(adminSignupService.login(any(), any()))
                .thenReturn("mocked-token");

        mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(content().string("mocked-token"));
    }

    @Test
    void login_shouldReturnNotFound_whenAdminDoesNotExist() throws Exception {

        when(adminSignupService.login(any(), any()))
                .thenThrow(new AdminNotFoundException("admin"));

        mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isNotFound());
    }

    @Test
    void login_shouldReturnUnauthorized_whenPasswordIncorrect() throws Exception {

        when(adminSignupService.login(any(), any()))
                .thenThrow(new WrongPasswordException());

        mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_shouldReturnBadRequest_whenRequestBodyMissing() throws Exception {

        mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_shouldReturnBadRequest_whenInvalidJson() throws Exception {

        mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json"))
                .andExpect(status().isBadRequest());
    }
}