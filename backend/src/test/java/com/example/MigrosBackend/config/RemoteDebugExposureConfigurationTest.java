package com.example.MigrosBackend.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Guards against network-exposed JDWP remote-debug configuration.
 *
 * <p>Ordinary {@code spring-boot:run}, Docker, CI and production must never enable a
 * Java debug agent. Local debugging is only allowed through the opt-in
 * {@code local-debug} Maven profile bound to {@code 127.0.0.1:5005}.</p>
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
        Element plugin = findDefaultSpringBootPlugin(pomDocument);
        assertThat(plugin)
                .as("expected spring-boot-maven-plugin under top-level <build> in backend/pom.xml")
                .isNotNull();

        String configurationText = pluginConfigurationText(plugin);
        assertThat(configurationText)
                .as("default spring-boot-maven-plugin configuration must not enable a debug agent, found: %s",
                        configurationText)
                .doesNotContain("-Xdebug")
                .doesNotContain("-Xrunjdwp")
                .doesNotContain("-agentlib:jdwp");
    }

    @Test
    void compose_doesNotPublishJdwpPort() {
        String backendSection = extractBackendServiceSection(composeText);
        assertThat(backendSection)
                .as("expected a backend service in compose.yaml")
                .isNotEmpty();

        String portsBlock = extractPortsBlock(backendSection);
        if (portsBlock.isEmpty()) {
            return;
        }

        assertThat(portsBlock)
                .as("backend service must not publish JDWP port 5005, ports block was: %s", portsBlock)
                .doesNotContain("5005");
    }

    @Test
    void localDebugProfile_isOptInAndLoopbackOnly() {
        Element profile = findProfileById(pomDocument, "local-debug");
        assertThat(profile)
                .as("expected an opt-in Maven profile with id 'local-debug' in backend/pom.xml")
                .isNotNull();

        assertThat(hasAutomaticActivation(profile))
                .as("profile 'local-debug' must be inactive by default with no automatic activation "
                        + "(activeByDefault, os, jdk, property, file)")
                .isFalse();

        Element plugin = findSpringBootPluginUnder(profile);
        assertThat(plugin)
                .as("profile 'local-debug' must configure spring-boot-maven-plugin")
                .isNotNull();

        String jvmArguments = pluginConfigurationText(plugin);
        assertThat(jvmArguments)
                .as("profile 'local-debug' must configure a JDWP agent, found: %s", jvmArguments)
                .contains("-agentlib:jdwp");

        assertThat(countOccurrences(jvmArguments, "-Xrunjdwp")).as(
                "profile 'local-debug' must not use legacy -Xrunjdwp flag, found: %s", jvmArguments).isZero();
        assertThat(countOccurrences(jvmArguments, "-Xdebug")).as(
                "profile 'local-debug' must not use legacy -Xdebug flag, found: %s", jvmArguments).isZero();
        assertThat(countOccurrences(jvmArguments, "-agentlib:jdwp")).as(
                "profile 'local-debug' must configure exactly one JDWP agent, found: %s", jvmArguments).isEqualTo(1);

        assertThat(jvmArguments).as(
                "profile 'local-debug' JDWP config must use server=y, suspend=n, address=127.0.0.1:5005, found: %s",
                jvmArguments)
                .contains("server=y")
                .contains("suspend=n")
                .contains("address=127.0.0.1:5005");

        assertThat(jvmArguments).as(
                "profile 'local-debug' must not bind JDWP to a wildcard interface, found: %s", jvmArguments)
                .doesNotContain("0.0.0.0")
                .doesNotContain("*:5005");

        String address = extractJdwpAddress(jvmArguments);
        assertThat(address).as(
                "profile 'local-debug' JDWP address must be exactly 127.0.0.1:5005, found: %s in %s",
                address, jvmArguments).isEqualTo("127.0.0.1:5005");
    }

    @Test
    void allRepositoryJdwpBindings_areLoopbackOnly() throws IOException {
        List<Path> candidates = collectRuntimeConfigurationFiles(repositoryRoot);
        List<String> violations = new ArrayList<>();

        Pattern jdwpMarker = Pattern.compile("(-Xrunjdwp|-agentlib:jdwp)");
        for (Path file : candidates) {
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                continue;
            }
            if (!jdwpMarker.matcher(content).find()) {
                continue;
            }
            for (String line : content.split("\\R")) {
                if (!line.contains("-Xrunjdwp") && !line.contains("-agentlib:jdwp")) {
                    continue;
                }
                String address = extractJdwpAddress(line);
                if (address == null || address.isEmpty()) {
                    violations.add(relativePath(file) + ": JDWP argument without explicit loopback address: "
                            + line.trim());
                } else if (!isLoopbackAddress(address)) {
                    violations.add(relativePath(file) + ": non-loopback JDWP address '" + address + "': "
                            + line.trim());
                }
            }
        }

        assertThat(violations).as(
                "all JDWP bindings in runtime/configuration files must be explicitly loopback-only").isEmpty();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Path locateRepositoryRoot() {
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

    private static Element findDefaultSpringBootPlugin(Document document) {
        Element root = document.getDocumentElement();
        Element build = directChild(root, "build");
        if (build == null) {
            return null;
        }
        Element plugins = directChild(build, "plugins");
        if (plugins == null) {
            return null;
        }
        for (Element plugin : directChildren(plugins, "plugin")) {
            if ("org.springframework.boot".equals(textOfDirectChild(plugin, "groupId"))
                    && "spring-boot-maven-plugin".equals(textOfDirectChild(plugin, "artifactId"))) {
                return plugin;
            }
        }
        return null;
    }

    private static Element findProfileById(Document document, String id) {
        Element root = document.getDocumentElement();
        Element profiles = directChild(root, "profiles");
        if (profiles == null) {
            return null;
        }
        for (Element profile : directChildren(profiles, "profile")) {
            if (id.equals(textOfDirectChild(profile, "id"))) {
                return profile;
            }
        }
        return null;
    }

    private static boolean hasAutomaticActivation(Element profile) {
        Element activation = directChild(profile, "activation");
        if (activation == null) {
            return false;
        }
        Element activeByDefault = directChild(activation, "activeByDefault");
        if (activeByDefault != null && "true".equalsIgnoreCase(activeByDefault.getTextContent().trim())) {
            return true;
        }
        // Any of os/jdk/property/file activation makes the profile non-opt-in.
        return directChild(activation, "os") != null
                || directChild(activation, "jdk") != null
                || directChild(activation, "property") != null
                || directChild(activation, "file") != null;
    }

    private static Element findSpringBootPluginUnder(Element scope) {
        Element build = directChild(scope, "build");
        if (build == null) {
            return null;
        }
        Element plugins = directChild(build, "plugins");
        if (plugins == null) {
            return null;
        }
        for (Element plugin : directChildren(plugins, "plugin")) {
            if ("spring-boot-maven-plugin".equals(textOfDirectChild(plugin, "artifactId"))) {
                String groupId = textOfDirectChild(plugin, "groupId");
                if (groupId == null || groupId.isEmpty() || "org.springframework.boot".equals(groupId)) {
                    return plugin;
                }
            }
        }
        return null;
    }

    private static String pluginConfigurationText(Element plugin) {
        Element configuration = directChild(plugin, "configuration");
        if (configuration == null) {
            return "";
        }
        return configuration.getTextContent() == null ? "" : configuration.getTextContent();
    }

    private static Element directChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(node.getLocalName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static List<Element> directChildren(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(node.getLocalName())) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private static String textOfDirectChild(Element parent, String localName) {
        Element child = directChild(parent, localName);
        if (child == null || child.getTextContent() == null) {
            return null;
        }
        return child.getTextContent().trim();
    }

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || haystack.isEmpty() || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String extractJdwpAddress(String text) {
        // Matches address=<host>:<port>, address=<port>, address=*:<port>, etc.
        Pattern pattern = Pattern.compile("address\\s*=\\s*([^,\\s\"']+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private static boolean isLoopbackAddress(String address) {
        String host = address;
        int colon = address.lastIndexOf(':');
        if (colon != -1 && !address.endsWith("]")) {
            host = address.substring(0, colon);
        }
        host = host.trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isEmpty() || host.equals("*") || host.equals("0.0.0.0")) {
            return false;
        }
        // Only explicit loopback hosts are accepted.
        if (host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost")) {
            return true;
        }
        if (host.equals("::1")) {
            return true;
        }
        return false;
    }

    private static String extractBackendServiceSection(String compose) {
        String[] lines = compose.split("\\R");
        StringBuilder section = new StringBuilder();
        boolean inBackend = false;
        for (String line : lines) {
            if (line.matches("^  backend:\\s*$")) {
                inBackend = true;
                section.append(line).append('\n');
                continue;
            }
            if (inBackend) {
                // Next sibling service (two-space indent) or top-level key ends the section.
                if (line.matches("^  [A-Za-z0-9_-]+:\\s*$") || line.matches("^[A-Za-z0-9_-]+:\\s*$")) {
                    break;
                }
                section.append(line).append('\n');
            }
        }
        return section.toString();
    }

    private static String extractPortsBlock(String backendSection) {
        String[] lines = backendSection.split("\\R");
        StringBuilder ports = new StringBuilder();
        boolean inPorts = false;
        for (String line : lines) {
            if (line.matches("^    ports:\\s*$")) {
                inPorts = true;
                ports.append(line).append('\n');
                continue;
            }
            if (inPorts) {
                // List items (six-space indent) belong to ports; a four-space key ends the block.
                if (line.matches("^    [A-Za-z0-9_-]+:.*$")) {
                    break;
                }
                ports.append(line).append('\n');
            }
        }
        if (!inPorts) {
            return "";
        }
        return ports.toString();
    }

    private static List<Path> collectRuntimeConfigurationFiles(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String normalized = path.toUri().toString().replace('\\', '/');
                if (normalized.contains("/.git/") || normalized.contains("/target/")
                        || normalized.contains("/node_modules/") || normalized.contains("/.idea/")
                        || normalized.contains("/src/test/") || normalized.contains("/test/fixtures")) {
                    return;
                }
                if (normalized.contains("/tasks/")) {
                    return;
                }
                String fileName = path.getFileName().toString();
                String full = root.relativize(path).toString().replace('\\', '/');
                boolean candidate = fileName.equals("pom.xml")
                        || fileName.equals("compose.yaml") || fileName.equals("compose.yml")
                        || fileName.equals("docker-compose.yaml") || fileName.equals("docker-compose.yml")
                        || fileName.startsWith("Dockerfile")
                        || fileName.endsWith(".sh") || fileName.endsWith(".cmd")
                        || fileName.endsWith(".bat") || fileName.endsWith(".ps1")
                        || (fileName.startsWith("application") && (fileName.endsWith(".properties")
                                || fileName.endsWith(".yml") || fileName.endsWith(".yaml")));
                if (candidate && !full.startsWith("client/")) {
                    // client/ holds frontend files; keep the scan focused on backend/runtime config,
                    // but still include root compose files matched above.
                    if (!full.equals("compose.yaml") && !full.equals("compose.yml")) {
                        result.add(path);
                    } else {
                        result.add(path);
                    }
                } else if (candidate) {
                    result.add(path);
                }
            });
        }
        return result;
    }

    private static String relativePath(Path file) {
        try {
            return repositoryRoot.relativize(file).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return file.toString();
        }
    }
}
