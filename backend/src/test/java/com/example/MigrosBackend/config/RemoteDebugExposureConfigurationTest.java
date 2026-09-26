package com.example.MigrosBackend.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Guards against network-exposed JDWP remote-debug configuration in the real
 * repository.
 *
 * <p>Ordinary {@code spring-boot:run}, Docker, CI and production must never
 * enable a Java debug agent. Local debugging is only allowed through the
 * opt-in {@code local-debug} Maven profile bound to {@code 127.0.0.1:5005}.</p>
 *
 * <p>The detection policy itself lives in {@link RemoteDebugExposureGuard} and
 * is adversarially exercised by {@code RemoteDebugExposureGuardTest}; this class
 * asserts the real checkout satisfies that policy.</p>
 */
class RemoteDebugExposureConfigurationTest {

    private static Path repositoryRoot;
    private static Document pomDocument;
    private static String composeText;

    @BeforeAll
    static void loadRepositoryConfiguration() throws Exception {
        repositoryRoot = locateRepositoryRoot();
        Path pomPath = repositoryRoot.resolve("backend/pom.xml");
        Path composePath = repositoryRoot.resolve("compose.yaml");

        assertThat(Files.isRegularFile(pomPath))
                .as("expected backend/pom.xml at %s", pomPath)
                .isTrue();
        assertThat(Files.isRegularFile(composePath))
                .as("expected compose.yaml at %s", composePath)
                .isTrue();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        pomDocument = factory.newDocumentBuilder().parse(pomPath.toFile());

        composeText = Files.readString(composePath, StandardCharsets.UTF_8);
    }

    @Test
    void defaultBuild_doesNotEnableAnyJavaDebugAgent() {
        Element plugin = RemoteDebugExposureGuard.findDefaultSpringBootPlugin(pomDocument);
        assertThat(plugin)
                .as("expected spring-boot-maven-plugin under top-level <build> in backend/pom.xml")
                .isNotNull();

        String configurationText = RemoteDebugExposureGuard.pluginConfigurationText(plugin);
        assertThat(RemoteDebugExposureGuard.containsDebugArgument(configurationText))
                .as("default spring-boot-maven-plugin configuration must not enable a debug agent, found: %s",
                        configurationText)
                .isFalse();
    }

    @Test
    void compose_doesNotPublishJdwpPort() {
        assertThat(RemoteDebugExposureGuard.composeViolations(composeText, "compose.yaml"))
                .as("compose.yaml must not publish the JDWP container port 5005 on the backend service")
                .isEmpty();
    }

    @Test
    void localDebugProfile_isOptInAndLoopbackOnly() {
        Element profile = RemoteDebugExposureGuard.findProfileById(
                pomDocument, RemoteDebugExposureGuard.LOCAL_DEBUG_PROFILE_ID);
        assertThat(profile)
                .as("expected an opt-in Maven profile with id 'local-debug' in backend/pom.xml")
                .isNotNull();

        assertThat(RemoteDebugExposureGuard.hasAutomaticActivation(profile))
                .as("profile 'local-debug' must be inactive by default with no automatic activation "
                        + "(activeByDefault, os, jdk, property, file)")
                .isFalse();

        Element plugin = RemoteDebugExposureGuard.findSpringBootPluginUnder(profile);
        assertThat(plugin)
                .as("profile 'local-debug' must configure spring-boot-maven-plugin")
                .isNotNull();

        String jvmArguments = RemoteDebugExposureGuard.pluginConfigurationText(plugin);
        assertThat(jvmArguments)
                .as("profile 'local-debug' must configure the reviewed loopback-only JDWP agent, found: %s",
                        jvmArguments)
                .contains("-agentlib:jdwp");

        assertThat(RemoteDebugExposureGuard.isAllowedLocalDebugConfiguration(jvmArguments))
                .as("profile 'local-debug' must be exactly the reviewed loopback-only JDWP argument, found: %s",
                        jvmArguments)
                .isTrue();

        assertThat(RemoteDebugExposureGuard.extractJdwpAddress(jvmArguments))
                .as("profile 'local-debug' JDWP address must be exactly 127.0.0.1:5005")
                .isEqualTo(RemoteDebugExposureGuard.ALLOWED_LOCAL_DEBUG_ADDRESS);
    }

    @Test
    void repositoryRuntimeFiles_doNotEnableAnyDebugAgent() throws Exception {
        assertThat(RemoteDebugExposureGuard.repositoryDebugViolations(repositoryRoot))
                .as("no tracked runtime/configuration file may enable a debug agent in a default launch surface")
                .isEmpty();
    }

    private static Path locateRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
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
}
