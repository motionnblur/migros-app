package com.example.MigrosBackend.controller.internal;

import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.support.SupportChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalSupportController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "support.internal.key=  padded-internal-key  ")
class InternalSupportControllerKeyNormalizationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SupportChatService supportChatService;

    @MockBean
    private AdminEntityRepository adminEntityRepository;

    @MockBean
    private TokenService tokenService;

    @Test
    void customersShouldAuthorizeWhenConfiguredKeyHasSurroundingWhitespace() throws Exception {
        when(supportChatService.searchSupportCustomers(null, null)).thenReturn(List.of());

        mockMvc.perform(get("/internal/support/customers")
                        .header("x-internal-key", "padded-internal-key"))
                .andExpect(status().isOk());
    }
}
