package com.example.MigrosBackend.controller.internal;

import com.example.MigrosBackend.filter.InternalApiKeyFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The key is now checked by {@code InternalApiKeyFilter}; this keeps coverage of
 * the configured-key whitespace normalization that used to live in the
 * controller.
 */
class InternalSupportControllerKeyNormalizationTest {

    @Test
    void customersShouldAuthorizeWhenConfiguredKeyHasSurroundingWhitespace() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("  padded-internal-key  ", new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/support/customers");
        request.setServletPath("/internal/support/customers");
        request.addHeader(InternalApiKeyFilter.HEADER_NAME, "padded-internal-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
