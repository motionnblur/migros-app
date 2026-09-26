package com.example.MigrosBackend.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Expresses the local runtime contracts that keep the full Docker stack and the
 * hybrid (host Maven + Angular) workflow deterministic:
 *
 * <ul>
 *   <li>Nginx reaches the backend through Compose DNS ({@code backend:8080}).</li>
 *   <li>The client proxy routes API traffic through Nginx, not around it.</li>
 *   <li>Compose derives its datasource from the Postgres service coordinates.</li>
 *   <li>The exact-local profile imports the two ignored developer env files and
 *       keeps a host-safe {@code localhost:5432} fallback.</li>
 *   <li>Example templates document every runtime input without real secrets.</li>
 *   <li>Outbound support configuration is forwarded, and its absence disables
 *       the integration instead of silently calling {@code localhost:3000}.</li>
 * </ul>
 *
 * <p>These tests must never print secret values.</p>
 */
class LocalRuntimeConfigurationTest {

    private static final Map<String, String> SECRET_PLACEHOLDERS = new LinkedHashMap<>();
    private static final Set<String> KNOWN_SECRET_PREFIXES = Set.of(
            "sk_live_", "sk_test_", "rk_live_", "rk_test_", "whsec_", "xoxb-", "AKIA", "-----BEGIN");

    static {
        SECRET_PLACEHOLDERS.put("JWT_USER_SECRET", "replace-with-");
        SECRET_PLACEHOLDERS.put("JWT_ADMIN_SECRET", "replace-with-");
        SECRET_PLACEHOLDERS.put("STRIPE_API_KEY", "replace-with-");
        SECRET_PLACEHOLDERS.put("STRIPE_WEBHOOK_SECRET", "replace-with-");
        SECRET_PLACEHOLDERS.put("SUPPORT_INTERNAL_KEY", "replace-with-");
        SECRET_PLACEHOLDERS.put("SUPPORT_SERVICE_INTERNAL_KEY", "replace-with-");
        SECRET_PLACEHOLDERS.put("POSTGRES_PASSWORD", "replace-with-");
        SECRET_PLACEHOLDERS.put("SPRING_DATASOURCE_PASSWORD", "replace-with-");
        SECRET_PLACEHOLDERS.put("MAIL_PASSWORD", "replace-with-");
        SECRET_PLACEHOLDERS.put("RESEND_API_KEY", "replace-with-");
    }

    private static final List<String> REQUIRED_TEMPLATE_KEYS = List.of(
            "JWT_USER_SECRET", "JWT_ADMIN_SECRET",
            "STRIPE_API_KEY", "STRIPE_WEBHOOK_SECRET",
            "SUPPORT_INTERNAL_KEY", "SUPPORT_SERVICE_BASE_URL", "SUPPORT_SERVICE_INTERNAL_KEY",
            "APP_ALLOWED_ORIGINS", "APP_UPLOAD_DIR",
            "POSTGRES_USER", "POSTGRES_PASSWORD", "POSTGRES_DB");

    private static Path repositoryRoot;
    private static Path composePath;
    private static Path nginxConfigPath;
    private static Path localPropertiesPath;

    @BeforeAll
    static void locateRepositoryFiles() {
        repositoryRoot = locateRepositoryRoot();
        composePath = repositoryRoot.resolve("compose.yaml");
        nginxConfigPath = repositoryRoot.resolve("configs/nginx/default.conf");
        localPropertiesPath = repositoryRoot.resolve("backend/src/main/resources/application-local.properties");

        assertThat(composePath).as("expected compose.yaml at %s", composePath).isRegularFile();
        assertThat(nginxConfigPath).as("expected nginx config at %s", nginxConfigPath).isRegularFile();
        assertThat(localPropertiesPath).as("expected local profile at %s", localPropertiesPath).isRegularFile();
    }

    @Test
    void nginxRoutesToComposeBackendService() throws IOException {
        String conf = Files.readString(nginxConfigPath, StandardCharsets.UTF_8);

        List<String> servers = nginxUpstreamServers(conf, "migros_backend");
        assertThat(servers)
                .as("nginx upstream 'migros_backend' must resolve the backend through Compose DNS")
                .contains("backend:8080");

        for (String server : servers) {
            String host = hostPart(server);
            assertThat(host)
                    .as("nginx upstream server '%s' must not use a host-only gateway or loopback address", server)
                    .isNotEqualTo("host.docker.internal")
                    .isNotEqualTo("localhost")
                    .isNotEqualTo("127.0.0.1");
        }
    }

    @Test
    void composeClientRoutesApiTrafficThroughNginx() throws IOException {
        Map<String, Object> compose = loadComposeYaml();

        String proxyTarget = serviceEnvironment(compose, "client").get("NG_PROXY_TARGET");
        assertThat(proxyTarget)
                .as("client proxy must route API traffic through nginx so edge controls are exercised")
                .isEqualTo("http://nginx:80");

        assertThat(dependencyNames(service(compose, "client")))
                .as("client startup must depend on nginx")
                .contains("nginx");
        assertThat(dependencyNames(service(compose, "nginx")))
                .as("nginx startup must depend on the backend")
                .contains("backend");

        List<String> backendHostPorts = publishedHostPorts(service(compose, "backend"));
        assertThat(backendHostPorts)
                .as("backend must stay internal; it must not publish application port 8080 to the host")
                .doesNotContain("8080");
    }

    @Test
    void composeBackendUsesPostgresServiceCoordinates() throws IOException {
        Map<String, Object> compose = loadComposeYaml();
        Map<String, String> backendEnv = serviceEnvironment(compose, "backend");

        String datasourceUrl = backendEnv.get("SPRING_DATASOURCE_URL");
        assertThat(datasourceUrl)
                .as("compose must define a backend datasource URL for the container path")
                .isNotNull();
        assertThat(jdbcHost(datasourceUrl))
                .as("backend datasource host must be the Compose service name 'postgres', url template was: %s",
                        datasourceUrl)
                .isEqualTo("postgres");
        assertThat(jdbcPort(datasourceUrl))
                .as("backend datasource port must be 5432, url template was: %s", datasourceUrl)
                .isEqualTo("5432");

        assertThat(backendEnv.get("SPRING_DATASOURCE_USERNAME"))
                .as("backend datasource username must be sourced from POSTGRES_USER, not embedded")
                .contains("${POSTGRES_USER");
        assertThat(backendEnv.get("SPRING_DATASOURCE_PASSWORD"))
                .as("backend datasource password must be sourced from POSTGRES_PASSWORD, not embedded")
                .contains("${POSTGRES_PASSWORD");
        assertThat(datasourceUrl)
                .as("backend database name must be sourced from POSTGRES_DB, not embedded")
                .contains("${POSTGRES_DB");

        Map<String, Object> postgres = service(compose, "postgres");
        assertThat(postgres)
                .as("compose must keep a postgres service")
                .isNotNull();
        assertThat(healthcheckTest(postgres))
                .as("postgres must keep its pg_isready health check")
                .contains("pg_isready");
        assertThat(dependencyNames(service(compose, "backend")))
                .as("backend must keep its dependency on postgres")
                .contains("postgres");
    }

    @Test
    void localProfileImportsOptionalDeveloperEnvFiles() throws IOException {
        Properties local = loadProperties(localPropertiesPath);

        String importValue = local.getProperty("spring.config.import");
        assertThat(importValue)
                .as("application-local.properties must optionally import the developer env files")
                .isNotNull()
                .contains("optional:file:../configs/spring.env")
                .contains("optional:file:../configs/postgres.env");

        assertThat(local.getProperty("spring.datasource.url"))
                .as("local datasource fallback must target the host-published Postgres at localhost:5432")
                .isNotNull()
                .contains("localhost:5432");
    }

    @Test
    void localConfigurationTemplatesCoverRuntimeInputsWithoutSecrets() throws IOException {
        String templates = readString(repositoryRoot.resolve("configs/spring.env.example"))
                + "\n" + readString(repositoryRoot.resolve("configs/postgres.env.example"));

        for (String requiredKey : REQUIRED_TEMPLATE_KEYS) {
            assertThat(templates)
                    .as("configuration templates must document %s", requiredKey)
                    .contains(requiredKey + "=");
        }

        assertThat(templates)
                .as("host-oriented example must not hard-code the Compose hostname 'postgres' in a JDBC URL")
                .doesNotContain("jdbc:postgresql://postgres");

        Map<String, String> springTemplate = parseEnvTemplate(repositoryRoot.resolve("configs/spring.env.example"));
        String hostJdbc = springTemplate.get("SPRING_DATASOURCE_URL");
        if (hostJdbc != null && !hostJdbc.isBlank()) {
            assertThat(hostJdbc)
                    .as("hybrid host template JDBC URL must use localhost, not the Compose-only hostname")
                    .contains("localhost:5432");
        }

        assertNoRealSecrets(springTemplate);
        assertNoRealSecrets(parseEnvTemplate(repositoryRoot.resolve("configs/postgres.env.example")));
    }

    @Test
    void composeForwardsOutboundSupportConfiguration() throws IOException {
        Map<String, Object> compose = loadComposeYaml();
        Map<String, String> backendEnv = serviceEnvironment(compose, "backend");

        assertThat(backendEnv)
                .as("compose must forward the optional outbound support configuration")
                .containsKey("SUPPORT_SERVICE_BASE_URL")
                .containsKey("SUPPORT_SERVICE_INTERNAL_KEY");

        assertThat(backendEnv.get("SUPPORT_SERVICE_BASE_URL"))
                .as("outbound support URL must be forwarded from the environment, never hard-coded")
                .contains("${SUPPORT_SERVICE_BASE_URL");
        assertThat(backendEnv.get("SUPPORT_SERVICE_INTERNAL_KEY"))
                .as("outbound support key must be forwarded from the environment, never hard-coded")
                .contains("${SUPPORT_SERVICE_INTERNAL_KEY");

        Properties local = loadProperties(localPropertiesPath);
        String localSupportUrl = local.getProperty("support.service.base-url");
        assertThat(localSupportUrl)
                .as("missing outbound support configuration must disable the integration rather than "
                        + "silently call localhost:3000 inside the backend container")
                .doesNotContain("localhost:3000");
    }

    /**
     * Resolves the exact nested placeholder expressions used by
     * {@code application-local.properties} through a real Spring
     * {@link Environment}, proving that the imported developer file supplies the
     * {@code POSTGRES_*} inputs and that a process environment variable wins.
     */
    @Test
    void localProfileNestedDatasourcePlaceholdersResolve(@TempDir Path tempDir) throws IOException {
        Properties local = loadProperties(localPropertiesPath);
        String urlExpression = local.getProperty("spring.datasource.url");
        String usernameExpression = local.getProperty("spring.datasource.username");
        String passwordExpression = local.getProperty("spring.datasource.password");
        assertThat(urlExpression).as("spring.datasource.url must be defined").isNotNull();
        assertThat(usernameExpression).as("spring.datasource.username must be defined").isNotNull();
        assertThat(passwordExpression).as("spring.datasource.password must be defined").isNotNull();

        Path envFile = tempDir.resolve("spring.env");
        Files.writeString(envFile, String.join("\n",
                "POSTGRES_DB=resolved_db",
                "POSTGRES_USER=resolved_user",
                "POSTGRES_PASSWORD=resolved_password",
                ""), StandardCharsets.UTF_8);
        String importLocation = "optional:file:"
                + envFile.toAbsolutePath().toString().replace('\\', '/') + "[.properties]";

        Map<String, String> expressions = new LinkedHashMap<>();
        expressions.put("spring.datasource.url", urlExpression);
        expressions.put("spring.datasource.username", usernameExpression);
        expressions.put("spring.datasource.password", passwordExpression);

        try (ConfigurableApplicationContext context = runLocalConfigContext(importLocation, expressions)) {
            Environment environment = context.getEnvironment();
            assertThat(environment.getProperty("spring.datasource.url"))
                    .as("nested POSTGRES_DB placeholder must resolve from the imported env file")
                    .isEqualTo("jdbc:postgresql://localhost:5432/resolved_db");
            assertThat(environment.getProperty("spring.datasource.username"))
                    .as("nested POSTGRES_USER placeholder must resolve from the imported env file")
                    .isEqualTo("resolved_user");
            assertThat(environment.getProperty("spring.datasource.password"))
                    .as("nested POSTGRES_PASSWORD placeholder must resolve from the imported env file")
                    .isEqualTo("resolved_password");
        }

        Map<String, String> withOverride = new LinkedHashMap<>(expressions);
        withOverride.put("SPRING_DATASOURCE_USERNAME", "process_override");
        try (ConfigurableApplicationContext context = runLocalConfigContext(importLocation, withOverride)) {
            assertThat(context.getEnvironment().getProperty("spring.datasource.username"))
                    .as("an explicit process environment variable must override the imported file")
                    .isEqualTo("process_override");
        }
    }

    static ConfigurableApplicationContext runLocalConfigContext(String importLocation,
            Map<String, String> injectedProperties) {
        Map<String, Object> source = new LinkedHashMap<>(injectedProperties);
        return new SpringApplicationBuilder(LocalRuntimeTestConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties("spring.config.import=" + importLocation)
                .initializers((ApplicationContextInitializer<ConfigurableApplicationContext>) applicationContext ->
                        applicationContext.getEnvironment().getPropertySources()
                                .addFirst(new MapPropertySource("local-runtime-test", source)))
                .run();
    }

    @Configuration
    static class LocalRuntimeTestConfiguration {
    }

    // ------------------------------------------------------------------
    // Package-private parsers and assertions
    // ------------------------------------------------------------------

    static Path locateRepositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path node = current;
        while (node != null) {
            if (Files.isRegularFile(node.resolve("compose.yaml"))
                    && Files.isRegularFile(node.resolve("backend/pom.xml"))) {
                return node;
            }
            node = node.getParent();
        }
        fail("could not locate repository root containing compose.yaml and backend/pom.xml starting from %s",
                current);
        return current;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> loadComposeYaml() throws IOException {
        try (InputStream input = Files.newInputStream(composePath)) {
            Object loaded = new Yaml().load(input);
            assertThat(loaded).as("compose.yaml must parse as a mapping").isInstanceOf(Map.class);
            return (Map<String, Object>) loaded;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> service(Map<String, Object> compose, String name) {
        Object services = compose.get("services");
        assertThat(services).as("compose.yaml must declare a services mapping").isInstanceOf(Map.class);
        Object service = ((Map<String, Object>) services).get(name);
        assertThat(service).as("compose.yaml must declare a '%s' service", name).isInstanceOf(Map.class);
        return (Map<String, Object>) service;
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> serviceEnvironment(Map<String, Object> compose, String serviceName) {
        Object environment = service(compose, serviceName).get("environment");
        Map<String, String> result = new LinkedHashMap<>();
        if (environment == null) {
            return result;
        }
        if (environment instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
            return result;
        }
        if (environment instanceof List<?> list) {
            for (Object entry : list) {
                String text = String.valueOf(entry);
                int separator = text.indexOf('=');
                if (separator < 0) {
                    result.put(text, "");
                } else {
                    result.put(text.substring(0, separator), text.substring(separator + 1));
                }
            }
            return result;
        }
        fail("unsupported 'environment' shape for service '%s'", serviceName);
        return result;
    }

    static List<String> dependencyNames(Map<String, Object> service) {
        Object dependsOn = service.get("depends_on");
        List<String> names = new ArrayList<>();
        if (dependsOn == null) {
            return names;
        }
        if (dependsOn instanceof List<?> list) {
            list.forEach(entry -> names.add(String.valueOf(entry)));
            return names;
        }
        if (dependsOn instanceof Map<?, ?> map) {
            map.keySet().forEach(key -> names.add(String.valueOf(key)));
            return names;
        }
        fail("unsupported 'depends_on' shape");
        return names;
    }

    static List<String> publishedHostPorts(Map<String, Object> service) {
        Object ports = service.get("ports");
        List<String> hostPorts = new ArrayList<>();
        if (!(ports instanceof List<?> list)) {
            return hostPorts;
        }
        for (Object entry : list) {
            String value;
            if (entry instanceof Map<?, ?> mapping) {
                Object published = mapping.get("published");
                if (published != null) {
                    value = String.valueOf(published);
                } else {
                    continue;
                }
            } else {
                value = String.valueOf(entry);
            }
            Matcher matcher = Pattern.compile("^(?:(?<ip>[^:]+):)?(?<host>\\d+):\\d+$").matcher(value.trim());
            if (matcher.matches()) {
                hostPorts.add(matcher.group("host"));
            }
        }
        return hostPorts;
    }

    static String healthcheckTest(Map<String, Object> service) {
        Object healthcheck = service.get("healthcheck");
        if (!(healthcheck instanceof Map<?, ?> mapping)) {
            return "";
        }
        Object test = mapping.get("test");
        if (test instanceof List<?> list) {
            return String.join(" ", list.stream().map(String::valueOf).toList());
        }
        return test == null ? "" : String.valueOf(test);
    }

    static List<String> nginxUpstreamServers(String conf, String upstreamName) {
        List<String> servers = new ArrayList<>();
        Pattern start = Pattern.compile("upstream\\s+" + Pattern.quote(upstreamName) + "\\s*\\{");
        boolean inUpstream = false;
        for (String rawLine : conf.split("\\R")) {
            String line = rawLine.trim();
            if (!inUpstream) {
                if (start.matcher(line).find()) {
                    inUpstream = true;
                }
                continue;
            }
            if (line.startsWith("}")) {
                break;
            }
            if (line.startsWith("server")) {
                int semicolon = line.indexOf(';');
                String body = semicolon < 0 ? line.substring("server".length()) : line.substring("server".length(), semicolon);
                String[] tokens = body.trim().split("\\s+");
                if (tokens.length > 0 && !tokens[0].isBlank()) {
                    servers.add(tokens[0]);
                }
            }
        }
        return servers;
    }

    static String hostPart(String server) {
        String host = server;
        int colon = server.lastIndexOf(':');
        if (colon > 0) {
            host = server.substring(0, colon);
        }
        return host;
    }

    static String jdbcHost(String url) {
        Matcher matcher = Pattern.compile("jdbc:postgresql://([^:/${}]+)").matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    static String jdbcPort(String url) {
        Matcher matcher = Pattern.compile("jdbc:postgresql://[^:/${}]+:(\\d+)").matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    static Properties loadProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    static Map<String, String> parseEnvTemplate(Path path) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : readString(path).split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator > 0) {
                values.put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim());
            }
        }
        return values;
    }

    static void assertNoRealSecrets(Map<String, String> templateValues) {
        templateValues.forEach((key, value) -> {
            for (String prefix : KNOWN_SECRET_PREFIXES) {
                assertThat(value)
                        .as("template value for %s must not contain a real secret prefix", key)
                        .doesNotContain(prefix);
            }
            String placeholderPrefix = SECRET_PLACEHOLDERS.get(key);
            if (placeholderPrefix != null && !value.isBlank()) {
                assertThat(value)
                        .as("template credential %s must be a documented placeholder, never a usable secret", key)
                        .contains(placeholderPrefix);
            }
        });
    }

    static String readString(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
