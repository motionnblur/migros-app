package com.example.MigrosBackend.controller.admin.sign;

import com.example.MigrosBackend.config.security.AuthCookieService;
import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.dto.admin.sign.AdminSignDto;
import com.example.MigrosBackend.exception.admin.AdminNotFoundException;
import com.example.MigrosBackend.exception.shared.WrongPasswordException;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.service.admin.sign.AdminSignupService;
import com.example.MigrosBackend.service.global.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminSignController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminSignControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminSignupService adminSignupService;

    @MockBean
    private AuthCookieService authCookieService;

    @MockBean
    private AuthTokenResolver authTokenResolver;

    @MockBean
    private TokenService tokenService;

    @Autowired
    private ObjectMapper objectMapper;

    private AdminSignDto dto;

    @BeforeEach
    void setup() {
        dto = new AdminSignDto();
        dto.setAdminName("admin");
        dto.setAdminPassword("password");
    }

    @Test
    void login_shouldSetSessionCookie_whenCredentialsValid() throws Exception {
        ResponseCookie cookie = ResponseCookie.from(AuthCookies.SESSION_COOKIE_NAME, "mocked-token")
                .httpOnly(true)
                .path("/")
                .maxAge(Duration.ofMinutes(3))
                .build();

        when(adminSignupService.login(any(), any())).thenReturn("mocked-token");
        when(authCookieService.createSessionCookie("mocked-token")).thenReturn(cookie);

        mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                 .andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.allOf(Matchers.containsString("migros_session="), Matchers.containsString("Max-Age=180"))));
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
    void logout_shouldClearSessionCookie() throws Exception {
        ResponseCookie cookie = ResponseCookie.from(AuthCookies.SESSION_COOKIE_NAME, "")
                .httpOnly(true)
                .path("/")
                .maxAge(Duration.ZERO)
                .build();

        when(authCookieService.clearSessionCookie()).thenReturn(cookie);

        mockMvc.perform(post("/admin/logout"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        Matchers.allOf(
                                Matchers.containsString("migros_session="),
                                Matchers.containsString("Max-Age=0"))));
    }

    @Test
    void session_shouldReturnAdminName() throws Exception {
        when(authTokenResolver.requireToken("session-token")).thenReturn("session-token");
        when(tokenService.extractUsername("session-token")).thenReturn("admin");

        mockMvc.perform(get("/admin/session")
                        .cookie(new Cookie(AuthCookies.SESSION_COOKIE_NAME, "session-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminName").value("admin"));
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



