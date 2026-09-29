package com.example.MigrosBackend.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class InternalApiKeyFilterTest {

    private static final String INTERNAL_KEY = "test-internal-key";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void constructorFailsWhenKeyIsNull() {
        assertThatThrownBy(() -> new InternalApiKeyFilter(null, objectMapper))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructorFailsWhenKeyIsBlank() {
        assertThatThrownBy(() -> new InternalApiKeyFilter("   ", objectMapper))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingHeaderIsRejectedWithUnauthorizedJson() throws Exception {
        MockHttpServletResponse response = filter(new InternalApiKeyFilter(INTERNAL_KEY, objectMapper),
                "/internal/support/customers", null);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/json");
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo(InternalApiKeyFilter.UNAUTHORIZED_CODE);
    }

    @Test
    void wrongKeyIsRejectedWithUnauthorized() throws Exception {
        MockHttpServletResponse response = filter(new InternalApiKeyFilter(INTERNAL_KEY, objectMapper),
                "/internal/support/customers", "wrong-key");

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void blankKeyHeaderIsRejectedWithUnauthorized() throws Exception {
        MockHttpServletResponse response = filter(new InternalApiKeyFilter(INTERNAL_KEY, objectMapper),
                "/internal/support/customers", "   ");

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void correctKeyPassesThroughToTheChain() throws Exception {
        FilterHarness harness = new FilterHarness(new InternalApiKeyFilter(INTERNAL_KEY, objectMapper),
                "/internal/support/customers", INTERNAL_KEY);

        harness.filter.doFilter(harness.request, harness.response, harness.chain);

        verify(harness.chain).doFilter(harness.request, harness.response);
        assertThat(harness.response.getStatus()).isEqualTo(200);
    }

    @Test
    void configuredKeyWhitespaceIsTrimmedBeforeComparison() throws Exception {
        FilterHarness harness = new FilterHarness(
                new InternalApiKeyFilter("  padded-internal-key  ", objectMapper),
                "/internal/support/customers", "padded-internal-key");

        harness.filter.doFilter(harness.request, harness.response, harness.chain);

        verify(harness.chain).doFilter(harness.request, harness.response);
    }

    @Test
    void nonInternalPathPassesThroughWithoutAnyKey() throws Exception {
        FilterHarness harness = new FilterHarness(new InternalApiKeyFilter(INTERNAL_KEY, objectMapper),
                "/user/supply/getAllCategoryNames", null);

        harness.filter.doFilter(harness.request, harness.response, harness.chain);

        verify(harness.chain).doFilter(harness.request, harness.response);
        assertThat(harness.response.getStatus()).isEqualTo(200);
    }

    @Test
    void internalPathWithMissingKeyNeverReachesTheChain() throws Exception {
        FilterHarness harness = new FilterHarness(new InternalApiKeyFilter(INTERNAL_KEY, objectMapper),
                "/internal/support/agent-message", null);

        harness.filter.doFilter(harness.request, harness.response, harness.chain);

        verify(harness.chain, never()).doFilter(harness.request, harness.response);
    }

    private MockHttpServletResponse filter(InternalApiKeyFilter filter, String path, String suppliedKey)
            throws Exception {
        FilterHarness harness = new FilterHarness(filter, path, suppliedKey);
        harness.filter.doFilter(harness.request, harness.response, harness.chain);
        return harness.response;
    }

    private static final class FilterHarness {
        private final InternalApiKeyFilter filter;
        private final MockHttpServletRequest request;
        private final MockHttpServletResponse response = new MockHttpServletResponse();
        private final FilterChain chain = mock(FilterChain.class);

        private FilterHarness(InternalApiKeyFilter filter, String path, String suppliedKey) {
            this.filter = filter;
            this.request = new MockHttpServletRequest("GET", path);
            this.request.setServletPath(path);
            if (suppliedKey != null) {
                this.request.addHeader(InternalApiKeyFilter.HEADER_NAME, suppliedKey);
            }
        }
    }
}
