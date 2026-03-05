package com.example.MigrosBackend.controller.user.payment;

import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.payment.UserPaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
    private TokenService tokenService;

    private Map<String, Object> payload;
    private Map<String, Object> response;

    @BeforeEach
    void setup() {
        payload = new HashMap<>();
        payload.put("amount", 1000);
        payload.put("currency", "USD");
        payload.put("source", "tok_visa");

        response = new HashMap<>();
        response.put("status", "success");
        response.put("chargeId", "ch_12345");
    }

    @Test
    void createCharge_shouldReturnOkWithResponse() throws Exception {
        // Mock the service
        when(userPaymentService.processCharge(any(Map.class))).thenReturn(response);

        mockMvc.perform(post("/payment/create-charge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(response)));
    }
}