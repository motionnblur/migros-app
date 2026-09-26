package com.example.MigrosBackend.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Protects the committed production environment contract.
 *
 * <p>The example file must document every property that
 * {@code application-prod.properties} references plus every mandatory
 * integration, must never contain a usable secret or a private endpoint, and
 * must not introduce insecure production fallbacks. This test never prints a
 * value it checks.</p>
 */
class ProductionConfigurationContractTest {

    private static final Set<String> KNOWN_SECRET_PREFIXES = Set.of(
            "sk_live_", "sk_test_", "rk_live_", "rk_test_", "whsec_", "xoxb-", "AKIA", "-----BEGIN");

    private static final Set<String> SECRET_KEYS = Set.of(
            "JWT_USER_SECRET", "JWT_ADMIN_SECRET", "STRIPE_API_KEY", "STRIPE_WEBHOOK_SECRET",
            "SUPPORT_INTERNAL_KEY", "SUPPORT_SERVICE_INTERNAL_KEY", "SPRING_DATASOURCE_PASSWORD",
            "RESEND_API_KEY", "MAIL_PASSWORD");

    private static final List<String> MANDATORY_INTEGRATION_KEYS = List.of(
            "SPRING_PROFILES_ACTIVE",
            "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
            "JWT_USER_SECRET", "JWT_ADMIN_SECRET",
            "STRIPE_API_KEY", "STRIPE_WEBHOOK_SECRET",
            "SUPPORT_INTERNAL_KEY", "SUPPORT_SERVICE_BASE_URL", "SUPPORT_SERVICE_INTERNAL_KEY",
            "APP_ALLOWED_ORIGINS", "APP_ALLOWED_ORIGIN_PATTERNS",
            "APP_FRONTEND_BASE_URL", "APP_BACKEND_BASE_URL",
            "RESEND_API_KEY", "APP_MAIL_FROM",
            "APP_UPLOAD_DIR");

    private static Path repositoryRoot;
    private static Path productionTemplatePath;
    private static Path prodPropertiesPath;
    private static Path basePropertiesPath;

    private static String productionTemplate;

    @BeforeAll
    static void locateRepositoryFiles() throws IOException {
        repositoryRoot = LocalRuntimeConfigurationTest.locateRepositoryRoot();
        productionTemplatePath = repositoryRoot.resolve("configs/production.env.example");
        prodPropertiesPath = repositoryRoot.resolve("backend/src/main/resources/application-prod.properties");
        basePropertiesPath = repositoryRoot.resolve("backend/src/main/resources/application.properties");

        assertThat(productionTemplatePath).as("expected %s", productionTemplatePath).isRegularFile();
        assertThat(prodPropertiesPath).as("expected %s", prodPropertiesPath).isRegularFile();
        assertThat(basePropertiesPath).as("expected %s", basePropertiesPath).isRegularFile();

        productionTemplate = Files.readString(productionTemplatePath, StandardCharsets.UTF_8);
    }

    @Test
    void exampleContainsEveryProductionPropertyAndMandatoryIntegration() throws IOException {
        Map<String, String> example = LocalRuntimeConfigurationTest.parseEnvTemplate(productionTemplatePath);

        List<String> referenced = referencedProperties(Files.readString(prodPropertiesPath, StandardCharsets.UTF_8));
        for (String property : referenced) {
            assertThat(example)
                    .as("production.env.example must document %s, referenced by application-prod.properties", property)
                    .containsKey(property);
        }

        for (String mandatory : MANDATORY_INTEGRATION_KEYS) {
            assertThat(example)
                    .as("production.env.example must document the mandatory integration %s", mandatory)
                    .containsKey(mandatory);
        }
    }

    @Test
    void exampleContainsNoRealSecretsOrPrivateEndpoints() throws IOException {
        Map<String, String> example = LocalRuntimeConfigurationTest.parseEnvTemplate(productionTemplatePath);

        example.forEach((key, value) -> {
            for (String prefix : KNOWN_SECRET_PREFIXES) {
                assertThat(value)
                        .as("production template value for %s must not contain a real secret prefix", key)
                        .doesNotContain(prefix);
            }
            String lower = value.toLowerCase();
            assertThat(lower)
                    .as("production template value for %s must not point at a loopback or private address", key)
                    .doesNotContain("localhost")
                    .doesNotContain("127.0.0.1")
                    .doesNotContain("0.0.0.0")
                    .doesNotContain("10.")
                    .doesNotContain("192.168.")
                    .doesNotContain(".local");
        });

        for (String secretKey : SECRET_KEYS) {
            assertThat(example)
                    .as("production template must document %s", secretKey)
                    .containsKey(secretKey);
            assertThat(example.get(secretKey))
                    .as("production template credential %s must remain a documented placeholder", secretKey)
                    .contains("replace-with");
        }
    }

    @Test
    void productionHasNoInsecureSecurityFallbacks() throws IOException {
        String prod = Files.readString(prodPropertiesPath, StandardCharsets.UTF_8);
        String base = Files.readString(basePropertiesPath, StandardCharsets.UTF_8);

        assertThat(prod)
                .as("production cookies must default to Secure")
                .doesNotContain("auth.cookie.secure=${AUTH_COOKIE_SECURE:false}");
        assertThat(prod).contains("auth.cookie.secure=${AUTH_COOKIE_SECURE:true}");

        assertThat(prod)
                .as("public origins must be required without an insecure fallback")
                .contains("app.allowed-origins=${APP_ALLOWED_ORIGINS}")
                .contains("app.frontend-base-url=${APP_FRONTEND_BASE_URL}")
                .contains("app.backend-base-url=${APP_BACKEND_BASE_URL}")
                .doesNotContain("AUTH_COOKIE_SECURE:false")
                .doesNotContain("localhost");

        assertThat(prod)
                .as("JWT secrets must have no usable fallback value")
                .contains("jwt.user-secret=${JWT_USER_SECRET:}")
                .contains("jwt.admin-secret=${JWT_ADMIN_SECRET:}");

        assertThat(base)
                .as("webhook verification must have no hard-coded signing secret")
                .contains("payment.webhook.secret=${STRIPE_WEBHOOK_SECRET:}")
                .doesNotContain("whsec_");

        for (String content : List.of(prod, base)) {
            assertThat(content)
                    .as("no administrator credential may be defaulted in configuration")
                    .doesNotContain("admin.password")
                    .doesNotContain("admin-password")
                    .doesNotContain("admin=admin");
        }
    }

    @Test
    void productionProfileIsExactlyProd() throws IOException {
        Map<String, String> example = LocalRuntimeConfigurationTest.parseEnvTemplate(productionTemplatePath);

        assertThat(example.get("SPRING_PROFILES_ACTIVE"))
                .as("SPRING_PROFILES_ACTIVE must be exactly prod")
                .isEqualTo("prod");

        assertThat(productionTemplate)
                .as("the template must document that adding local changes the exact-local safety policy")
                .contains("exactly")
                .contains("{local}")
                .contains("non-local");
    }

    @Test
    void productionPublicOriginsUseHttps() throws IOException {
        Map<String, String> example = LocalRuntimeConfigurationTest.parseEnvTemplate(productionTemplatePath);

        for (String originKey : List.of("APP_FRONTEND_BASE_URL", "APP_BACKEND_BASE_URL", "APP_ALLOWED_ORIGINS")) {
            String value = example.get(originKey);
            assertThat(value).as("%s must be configured in the production template", originKey).isNotBlank();
            for (String entry : value.split(",")) {
                assertThat(entry.trim())
                        .as("%s entry must be an absolute HTTPS origin", originKey)
                        .startsWith("https://");
            }
        }

        String patterns = example.getOrDefault("APP_ALLOWED_ORIGIN_PATTERNS", "");
        if (!patterns.isBlank()) {
            for (String entry : patterns.split(",")) {
                assertThat(entry.trim())
                        .as("APP_ALLOWED_ORIGIN_PATTERNS entry must be an absolute HTTPS origin")
                        .startsWith("https://");
            }
        }
    }

    private static List<String> referencedProperties(String propertiesContent) {
        Matcher matcher = Pattern.compile("\\$\\{([A-Z0-9_]+)").matcher(propertiesContent);
        List<String> properties = new ArrayList<>();
        while (matcher.find()) {
            properties.add(matcher.group(1));
        }
        return properties.stream().distinct().collect(Collectors.toList());
    }
}
