package com.example.MigrosBackend.controller.user.payment;

import com.example.MigrosBackend.config.GlobalExceptionHandler;
import com.example.MigrosBackend.config.security.SecurityConfiguration;
import com.example.MigrosBackend.exception.user.WebhookSignatureException;
import com.example.MigrosBackend.filter.JwtRequestFilter;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.payment.PaymentWebhookService;
import com.example.MigrosBackend.service.user.payment.StripeWebhookVerifier;
import com.stripe.model.Event;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StripeWebhookController.class)
@AutoConfigureMockMvc
@Import({SecurityConfiguration.class, JwtRequestFilter.class, GlobalExceptionHandler.class})
class StripeWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StripeWebhookVerifier verifier;

    @MockBean
    private PaymentWebhookService paymentWebhookService;

    @MockBean
    private TokenService tokenService;

    @MockBean
    private AdminEntityRepository adminEntityRepository;

    @Test
    void validSignatureIsAcceptedWithoutAUserCookie() throws Exception {
        Event event = org.mockito.Mockito.mock(Event.class);
        when(verifier.verify(eq("{}"), eq("t=1,v1=valid"))).thenReturn(event);

        mockMvc.perform(post("/payment/webhook")
                        .header("Stripe-Signature", "t=1,v1=valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        verify(paymentWebhookService).handle(event);
    }

    @Test
    void invalidSignatureIsRejectedWithBadRequest() throws Exception {
        when(verifier.verify(any(), eq("t=1,v1=bad")))
                .thenThrow(new WebhookSignatureException("Invalid Stripe webhook signature"));

        mockMvc.perform(post("/payment/webhook")
                        .header("Stripe-Signature", "t=1,v1=bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(paymentWebhookService, never()).handle(any());
    }

    @Test
    void missingSignatureIsRejected() throws Exception {
        when(verifier.verify(any(), eq(null)))
                .thenThrow(new WebhookSignatureException("Missing Stripe signature header"));

        mockMvc.perform(post("/payment/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(paymentWebhookService, never()).handle(any());
    }
}
