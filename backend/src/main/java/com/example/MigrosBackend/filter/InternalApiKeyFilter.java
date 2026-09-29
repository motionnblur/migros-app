package com.example.MigrosBackend.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Guards the backend-only internal support bridge with the shared
 * {@code x-internal-key}. Only {@code /internal/**} requests are inspected;
 * everything else passes through untouched, so the filter can be registered in
 * the security chain without affecting any other route.
 *
 * <p>The configured key is compared in constant time against the supplied
 * header. A blank configured key fails bean creation, so the bridge can never
 * start in an open state. Failures are reported as a 401 JSON body using the
 * same stable {@code {"code": ...}} shape as the CSRF denial handler.</p>
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "x-internal-key";
    private static final String INTERNAL_PATH_PREFIX = "/internal/";
    private static final String INTERNAL_PATH_ROOT = "/internal";
    static final String UNAUTHORIZED_CODE = "UNAUTHORIZED";

    private final byte[] internalKeyBytes;
    private final ObjectMapper objectMapper;

    public InternalApiKeyFilter(String internalKey, ObjectMapper objectMapper) {
        String normalized = internalKey == null ? "" : internalKey.trim();
        if (normalized.isBlank()) {
            throw new IllegalStateException(
                    "support.internal.key (SUPPORT_INTERNAL_KEY) must be configured; "
                    + "refusing to start with an open internal support bridge.");
        }
        this.internalKeyBytes = normalized.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = resolveRequestPath(request);
        return !path.startsWith(INTERNAL_PATH_PREFIX) && !path.equals(INTERNAL_PATH_ROOT);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String suppliedKey = request.getHeader(HEADER_NAME);
        if (suppliedKey != null && !suppliedKey.isBlank()
                && MessageDigest.isEqual(internalKeyBytes, suppliedKey.getBytes(StandardCharsets.UTF_8))) {
            filterChain.doFilter(request, response);
            return;
        }

        writeUnauthorized(response);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Map.of("code", UNAUTHORIZED_CODE));
    }

    private String resolveRequestPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isBlank()) {
            return servletPath;
        }

        String requestUri = request.getRequestURI();
        return requestUri != null ? requestUri : "";
    }
}
