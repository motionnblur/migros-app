package com.example.MigrosBackend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link PublicUrlProperties} for constructor binding.
 *
 * <p>Constructor-bound configuration properties cannot be picked up by component
 * scanning, so they are enabled explicitly here. This lives in its own
 * {@code @Configuration} class rather than on the application class because a
 * {@code @WebMvcTest} slice would otherwise process the import and try to bind
 * both public origins from a test environment that does not define them.
 */
@Configuration
@EnableConfigurationProperties(PublicUrlProperties.class)
public class PublicUrlConfiguration {
}
