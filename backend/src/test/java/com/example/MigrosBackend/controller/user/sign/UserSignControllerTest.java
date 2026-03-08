package com.example.MigrosBackend.controller.user.sign;

import com.example.MigrosBackend.config.security.AuthCookieService;
import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.dto.user.sign.UserSignDto;
import com.example.MigrosBackend.exception.shared.TokenNotFoundException;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.sign.UserSignupService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    private AuthCookieService authCookieService;

    @MockBean
    private AuthTokenResolver authTokenResolver;

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
                .andExpect(status().isOk());
    }

    @Test
    void login_shouldSetSessionCookie() throws Exception {
        ResponseCookie cookie = ResponseCookie.from(AuthCookies.USER_SESSION_COOKIE_NAME, "mock-jwt-token")
                .httpOnly(true)
                .path("/")
                .maxAge(Duration.ofMinutes(3))
                .build();

        when(userSignupService.login(any(UserSignDto.class))).thenReturn("mock-jwt-token");
        when(authCookieService.createUserSessionCookie("mock-jwt-token")).thenReturn(cookie);

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userSignDto)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        Matchers.allOf(
                                Matchers.containsString("migros_user_session="),
                                Matchers.containsString("Max-Age=180"))));
    }

    @Test
    void logout_shouldClearUserSessionCookie() throws Exception {
        ResponseCookie cookie = ResponseCookie.from(AuthCookies.USER_SESSION_COOKIE_NAME, "")
                .httpOnly(true)
                .path("/")
                .maxAge(Duration.ZERO)
                .build();

        when(authCookieService.clearUserSessionCookie()).thenReturn(cookie);

        mockMvc.perform(post("/user/logout"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        Matchers.allOf(
                                Matchers.containsString("migros_user_session="),
                                Matchers.containsString("Max-Age=0"))));
    }

    @Test
    void session_shouldReturnUserMail() throws Exception {
        when(authTokenResolver.requireToken("session-token")).thenReturn("session-token");
        when(tokenService.extractUsername("session-token")).thenReturn("test@example.com");

        mockMvc.perform(get("/user/session")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "session-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userMail").value("test@example.com"));
    }

    @Test
    void session_shouldReturnNotFound_whenCookieMissing() throws Exception {
        when(authTokenResolver.requireToken(null)).thenThrow(new TokenNotFoundException());

        mockMvc.perform(get("/user/session"))
                .andExpect(status().isNotFound());
    }
}


