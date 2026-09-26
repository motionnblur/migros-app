package com.example.MigrosBackend.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsrfAccessDeniedHandlerTest {

    private final CsrfAccessDeniedHandler handler =
            new CsrfAccessDeniedHandler(new ObjectMapper());

    @Test
    void missingCsrfTokenWritesStableErrorCode() throws Exception {
        MockHttpServletResponse response =
                handle(new MissingCsrfTokenException("missing"));

        assertEquals(HttpStatus.FORBIDDEN.value(), response.getStatus());
        assertNotNull(response.getContentType());
        assertTrue(response.getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE));
        assertTrue(response.getContentAsString().contains("\"code\":\"CSRF_INVALID\""));
    }

    @Test
    void invalidCsrfTokenWritesStableErrorCode() throws Exception {
        MockHttpServletResponse response = handle(new InvalidCsrfTokenException(
                new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "token"), "invalid"));

        assertEquals(HttpStatus.FORBIDDEN.value(), response.getStatus());
        assertNotNull(response.getContentType());
        assertTrue(response.getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE));
        assertTrue(response.getContentAsString().contains("\"code\":\"CSRF_INVALID\""));
    }

    @Test
    void genericAccessDeniedIsDelegatedWithoutCsrfCode() throws Exception {
        MockHttpServletResponse response =
                handle(new AccessDeniedException("ordinary denial"));

        assertEquals(HttpStatus.FORBIDDEN.value(), response.getStatus());
        assertFalse(response.getContentAsString().contains("CSRF_INVALID"));
    }

    private MockHttpServletResponse handle(AccessDeniedException exception) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.handle(new MockHttpServletRequest(), response, exception);
        return response;
    }
}
