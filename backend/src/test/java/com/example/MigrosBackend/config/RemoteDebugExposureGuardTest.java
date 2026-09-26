package com.example.MigrosBackend.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial fixture tests for {@link RemoteDebugExposureGuard}.
 *
 * <p>Every fixture here is hermetic: Compose and Maven content is supplied as a
 * string and repository files are written to a temporary directory, so the guard
 * is proven without touching the real checkout. Each malicious fixture must be
 * rejected; the safe fixtures prove the guard is not a blanket "any 5005" rule.</p>
 */
class RemoteDebugExposureGuardTest {

    private static final String ALLOWED_ARGUMENT = RemoteDebugExposureGuard.ALLOWED_LOCAL_DEBUG_ARGUMENT;

    // ------------------------------------------------------------------
    // Compose representations
    // ------------------------------------------------------------------

    @Test
    void composeInlinePortListContainerPort5005_isRejected() {
        assertRejected("""
                services:
                  backend:
                    image: app
                    ports: ["5005:5005"]
                """);
    }

    @Test
    void composeInlineRemappedContainerPort5005_isRejected() {
        assertRejected("""
                services:
                  backend:
                    image: app
                    ports: ["15005:5005"]
                """);
    }

    @Test
    void composeShortBlockSyntaxContainerPort5005_isRejected() {
        assertRejected(composeWithBackendPorts("      - \"5005:5005\"\n"));
    }

    @Test
    void composeRemappedHostPortStillPublishingContainerPort5005_isRejected() {
        assertRejected(composeWithBackendPorts("      - \"15005:5005\"\n"));
    }

    @Test
    void composeIpQualifiedContainerPort5005_isRejectedEvenWhenLoopbackBound() {
        assertRejected(composeWithBackendPorts("      - \"127.0.0.1:5005:5005\"\n"));
    }

    @Test
    void composeLongSyntaxTargetPort5005_isRejected() {
        assertRejected(composeWithBackendPorts("""
                      - target: 5005
                        published: 15005
                        protocol: tcp
                """));
    }

    @Test
    void composeAnchorAndMergeInheritedContainerPort5005_isRejected() {
        String compose = """
                x-debug-ports: &debug_ports
                  ports:
                    - "5005:5005"
                services:
                  backend:
                    image: app
                    <<: *debug_ports
                """;
        assertRejected(compose);
    }

    @Test
    void composeServiceAliasAndMergeInheritedContainerPort5005_isRejected() {
        String compose = """
                services:
                  base: &base
                    image: app
                    ports:
                      - "5005:5005"
                  backend:
                    <<: *base
                """;
        assertRejected(compose);
    }

    @Test
    void composeBackendExtends_isRejectedBecauseCrossFileMergeCannotBeVerified() {
        String compose = """
                services:
                  backend:
                    image: app
                    extends:
                      file: ../shared/compose.yaml
                      service: base
                """;
        assertRejected(compose);
    }

    @Test
    void composeExposingOnlyApplicationPort8080_isAccepted() {
        String compose = composeWithBackendPorts("      - \"8080:8080\"\n");
        assertThat(RemoteDebugExposureGuard.composeViolations(compose, "compose.yaml"))
                .as("unrelated container port 8080 must not be treated as a JDWP publication")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // JVM argument injection surfaces
    // ------------------------------------------------------------------

    @Test
    void mvnJvmConfigWithWildcardBoundJdwp_isRejected(@TempDir Path root) throws Exception {
        write(root, ".mvn/jvm.config",
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005\n");
        assertRejected(root, ".mvn/jvm.config");
    }

    @Test
    void extensionlessMvnwWithWildcardRunjdwp_isRejected(@TempDir Path root) throws Exception {
        write(root, "mvnw",
                "#!/bin/sh\nexec java -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=0.0.0.0:5005 "
                        + "-jar app.jar\n");
        assertRejected(root, "mvnw");
    }

    @Test
    void wrapperDockerfilePowerShellAndShellDebugArguments_areRejected(@TempDir Path root) throws Exception {
        write(root, "mvnw.cmd",
                "@java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:5005 -jar app.jar\r\n");
        write(root, "Dockerfile",
                "ENV JAVA_TOOL_OPTIONS=\"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:5005\"\n");
        write(root, "deploy/deploy.ps1",
                "$env:MAVEN_OPTS='-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005'\n");
        write(root, "scripts/run.sh",
                "export MAVEN_OPTS=\"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005\"\n");

        List<String> violations = RemoteDebugExposureGuard.repositoryDebugViolations(root);
        assertThat(violations)
                .as("wrapper/Dockerfile/PowerShell/shell debug arguments must all be rejected")
                .isNotEmpty();

        List<String> offendingFiles = violations.stream()
                .map(violation -> violation.substring(0, violation.indexOf(':')))
                .collect(Collectors.toList());
        assertThat(offendingFiles)
                .as("each debug-injection surface must be reported individually")
                .contains("mvnw.cmd", "Dockerfile", "deploy/deploy.ps1", "scripts/run.sh");
    }

    @Test
    void safeMvnJvmConfigWithLoopbackAddressStillRejectedInDefaultLaunchSurface(@TempDir Path root) throws Exception {
        write(root, ".mvn/jvm.config",
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005\n");
        assertThat(RemoteDebugExposureGuard.repositoryDebugViolations(root))
                .as("a default launch surface must not enable a debugger even on loopback; opt-in only")
                .isNotEmpty();
    }

    // ------------------------------------------------------------------
    // Default-vs-opt-in Maven behavior
    // ------------------------------------------------------------------

    @Test
    void defaultTopLevelSpringBootPluginWithJdwp_isRejected() throws Exception {
        String pom = pom(defaultBuildPlugin(wildcardArgument()), "");
        assertThat(RemoteDebugExposureGuard.mavenPomViolations(parsePom(pom), "fixture-pom.xml"))
                .as("JDWP in the default spring-boot-maven-plugin must be rejected")
                .isNotEmpty();
    }

    @Test
    void inactiveLocalDebugProfileWithExactLoopbackArgument_isAccepted() throws Exception {
        String pom = pom("", localDebugProfile(null, ALLOWED_ARGUMENT));
        assertThat(RemoteDebugExposureGuard.mavenPomViolations(parsePom(pom), "fixture-pom.xml"))
                .as("the reviewed inactive loopback-only local-debug profile is allowed")
                .isEmpty();
    }

    @Test
    void reviewedOptInProfileWithOnlyLoopbackAddress_isAccepted() throws Exception {
        String pom = pom("", localDebugProfile(null,
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005"));
        assertThat(RemoteDebugExposureGuard.mavenPomViolations(parsePom(pom), "fixture-pom.xml"))
                .as("only address=127.0.0.1:5005 in the reviewed opt-in profile is safe")
                .isEmpty();
    }

    @ParameterizedTest(name = "activation: {0}")
    @ValueSource(strings = {
            "<activeByDefault>true</activeByDefault>",
            "<os><family>windows</family></os>",
            "<jdk>[21,)</jdk>",
            "<property><name>debug</name></property>",
            "<file><exists>${project.basedir}/debug.flag</exists></file>"
    })
    void automaticallyActivatedLocalDebugProfile_isRejected(String activation) throws Exception {
        String pom = pom("", localDebugProfile(activation, ALLOWED_ARGUMENT));
        assertThat(RemoteDebugExposureGuard.mavenPomViolations(parsePom(pom), "fixture-pom.xml"))
                .as("profile 'local-debug' must not declare automatic activation: %s", activation)
                .isNotEmpty();
    }

    @ParameterizedTest(name = "jdwp address: {0}")
    @ValueSource(strings = {
            "5005",
            "*:5005",
            "0.0.0.0:5005",
            "[::]:5005",
            "debug.example.lan:5005"
    })
    void nonLoopbackJdwpAddressFormsInLocalDebugProfile_areRejected(String address) throws Exception {
        String argument = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=" + address;
        String pom = pom("", localDebugProfile(null, argument));
        assertThat(RemoteDebugExposureGuard.mavenPomViolations(parsePom(pom), "fixture-pom.xml"))
                .as("profile 'local-debug' must reject non-loopback JDWP address '%s'", address)
                .isNotEmpty();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void assertRejected(String compose) {
        List<String> violations = RemoteDebugExposureGuard.composeViolations(compose, "fixture-compose.yaml");
        assertThat(violations)
                .as("compose fixture must be rejected for publishing JDWP container port 5005:\n%s", compose)
                .isNotEmpty();
    }

    private static void assertRejected(Path root, String expectedFile) throws Exception {
        List<String> violations = RemoteDebugExposureGuard.repositoryDebugViolations(root);
        assertThat(violations)
                .as("fixture file %s must be rejected for enabling a debug agent", expectedFile)
                .isNotEmpty();
        assertThat(violations.stream().map(v -> v.substring(0, v.indexOf(':'))).collect(Collectors.toList()))
                .as("fixture file %s must be individually reported", expectedFile)
                .contains(expectedFile);
    }

    private static String composeWithBackendPorts(String portsItems) {
        return "services:\n"
                + "  backend:\n"
                + "    image: app\n"
                + "    ports:\n"
                + portsItems;
    }

    private static String wildcardArgument() {
        return "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005";
    }

    private static String pom(String buildPlugins, String profiles) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <build>" + buildPlugins + "</build>\n"
                + "  <profiles>" + profiles + "</profiles>\n"
                + "</project>\n";
    }

    private static String defaultBuildPlugin(String jvmArguments) {
        return "<plugins><plugin>"
                + "<groupId>org.springframework.boot</groupId>"
                + "<artifactId>spring-boot-maven-plugin</artifactId>"
                + "<configuration><jvmArguments>" + jvmArguments + "</jvmArguments></configuration>"
                + "</plugin></plugins>";
    }

    private static String localDebugProfile(String activation, String jvmArguments) {
        String activationXml = activation == null ? "" : "<activation>" + activation + "</activation>";
        return "<profile><id>local-debug</id>" + activationXml
                + "<build><plugins><plugin>"
                + "<groupId>org.springframework.boot</groupId>"
                + "<artifactId>spring-boot-maven-plugin</artifactId>"
                + "<configuration><jvmArguments>" + jvmArguments + "</jvmArguments></configuration>"
                + "</plugin></plugins></build></profile>";
    }

    private static Document parsePom(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static void write(Path root, String relativePath, String content) throws Exception {
        Path target = root.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
