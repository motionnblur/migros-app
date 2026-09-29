package com.example.MigrosBackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Validated public origins for the links the backend hands to users.
 *
 * <p>Constructor-bound: a missing or malformed origin fails bean creation (and
 * therefore startup) as soon as the properties are bound, instead of relying on
 * a later {@code @PostConstruct} pass. The bind prefix stays {@code app} because
 * the two origins are declared as {@code app.frontend-base-url} and
 * {@code app.backend-base-url} in every profile; registration is explicit via
 * {@code @EnableConfigurationProperties} on the application class, which is
 * what constructor binding requires.
 */
@ConfigurationProperties(prefix = "app")
public record PublicUrlProperties(String backendBaseUrl, String frontendBaseUrl) {

    static final String FRONTEND_PROPERTY = "app.frontend-base-url";
    static final String BACKEND_PROPERTY = "app.backend-base-url";

    public PublicUrlProperties {
        normalizeOrigin(BACKEND_PROPERTY, backendBaseUrl);
        normalizeOrigin(FRONTEND_PROPERTY, frontendBaseUrl);
    }

    public String normalizedBackendBaseUrl() {
        return normalizeOrigin(BACKEND_PROPERTY, backendBaseUrl);
    }

    public String normalizedFrontendBaseUrl() {
        return normalizeOrigin(FRONTEND_PROPERTY, frontendBaseUrl);
    }

    private static String normalizeOrigin(String propertyName, String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalStateException(
                    propertyName + " must be configured with an absolute http(s) origin");
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(propertyName + " must be a valid absolute http(s) origin", ex);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalStateException(
                    propertyName + " must use the http or https scheme but was: " + trimmed);
        }

        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalStateException(propertyName + " must include a host but was: " + trimmed);
        }

        if (uri.getUserInfo() != null) {
            throw new IllegalStateException(
                    propertyName + " must not contain embedded credentials but was: " + trimmed);
        }

        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException(
                    propertyName + " must not contain a query string or fragment but was: " + trimmed);
        }

        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalStateException(
                    propertyName + " must not contain a path but was: " + trimmed);
        }

        String normalized = trimmed;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
