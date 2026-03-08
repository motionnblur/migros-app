package com.example.MigrosBackend.controller.user.payment;

import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.exception.shared.TokenNotFoundException;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.payment.UserPaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
class PaymentControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserPaymentService userPaymentService;

    @MockBean
    private AuthTokenResolver authTokenResolver;

    @MockBean
    private TokenService tokenService;

    private Map<String, Object> payload;
    private Map<String, Object> response;

    @BeforeEach
    void setup() {
        payload = new HashMap<>();
        payload.put("token", "tok_visa");

        response = new HashMap<>();
        response.put("status", "success");
        response.put("chargeId", "ch_12345");
    }

    @Test
    void createCharge_shouldReturnOkWithResponse() throws Exception {
        when(authTokenResolver.requireToken("sample-token")).thenReturn("sample-token");
        when(userPaymentService.processCharge(eq(payload), eq("sample-token"))).thenReturn(response);

        mockMvc.perform(post("/payment/create-charge")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "sample-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(response)));
    }

    @Test
    void createCharge_shouldReturnNotFound_whenCookieMissing() throws Exception {
        when(authTokenResolver.requireToken(null)).thenThrow(new TokenNotFoundException());

        mockMvc.perform(post("/payment/create-charge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isNotFound());
    }

    @Test
    void createCharge_shouldReturnEmptyBody_whenServiceReturnsNull() throws Exception {
        when(authTokenResolver.requireToken("sample-token")).thenReturn("sample-token");
        when(userPaymentService.processCharge(eq(payload), eq("sample-token"))).thenReturn(null);

        mockMvc.perform(post("/payment/create-charge")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, "sample-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }
}
