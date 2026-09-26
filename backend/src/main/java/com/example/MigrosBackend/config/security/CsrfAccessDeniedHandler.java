package com.example.MigrosBackend.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Distinguishes failures raised by Spring's CSRF filter from ordinary
 * authorization denials. CSRF failures are reported with the stable,
 * machine-readable {@code {"code":"CSRF_INVALID"}} body so the SPA can refresh
 * its token and replay exactly once; every other access-denied exception is
 * delegated to Spring's standard handler so their responses are unchanged.
 *
 * <p>Spring Security installs the configured access-denied handler on both
 * {@code ExceptionTranslationFilter} and {@code CsrfFilter}, so this class is
 * the single place that separates the two cases.</p>
 */
public class CsrfAccessDeniedHandler implements AccessDeniedHandler {

    static final String CSRF_ERROR_CODE = "CSRF_INVALID";

    private final ObjectMapper objectMapper;
    private final AccessDeniedHandler delegate = new AccessDeniedHandlerImpl();

    public CsrfAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException, ServletException {
        if (accessDeniedException instanceof CsrfException) {
            writeCsrfFailure(response);
            return;
        }
        delegate.handle(request, response, accessDeniedException);
    }

    private void writeCsrfFailure(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Map.of("code", CSRF_ERROR_CODE));
    }
}
