package com.example.MigrosBackend.config;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Shared, package-private policy used by the JDWP regression tests.
 *
 * <p>This class intentionally has no JUnit dependencies. Its Compose scanner
 * accepts raw content so callers can exercise it with hermetic fixtures, and
 * its repository scanner accepts a repository root so it can run against a
 * temporary fixture tree as well as the real checkout.</p>
 *
 * <p>Policy summary:</p>
 * <ul>
 *   <li>Compose must never publish the JDWP container port {@code 5005} on the
 *       {@code backend} service, in any representation.</li>
 *   <li>No default launch surface (wrapper scripts, {@code .mvn/jvm.config},
 *       Dockerfiles, CI, app config) may enable a debug agent, even on
 *       loopback.</li>
 *   <li>The only permitted debugger is the single reviewed, inactive
 *       {@code local-debug} Maven profile argument bound to
 *       {@code 127.0.0.1:5005}.</li>
 * </ul>
 */
final class RemoteDebugExposureGuard {

    static final String LOCAL_DEBUG_PROFILE_ID = "local-debug";
    static final String ALLOWED_LOCAL_DEBUG_ADDRESS = "127.0.0.1:5005";
    static final String ALLOWED_LOCAL_DEBUG_ARGUMENT =
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=" + ALLOWED_LOCAL_DEBUG_ADDRESS;

    private static final int DEBUG_PORT = 5005;
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_WALK_DEPTH = 16;

    private static final Pattern DEBUG_MARKER =
            Pattern.compile("-Xdebug|-Xrunjdwp|-agentlib:jdwp", Pattern.CASE_INSENSITIVE);
    private static final Pattern JDWP_ADDRESS =
            Pattern.compile("address\\s*=\\s*([^,\\s\"']+)");
    private static final Pattern COMPOSE_FILE =
            Pattern.compile("^(docker-)?compose.*\\.ya?ml$");
    private static final Pattern CI_YAML =
            Pattern.compile(".*\\.ya?ml$");

    private static final List<String> EXCLUDED_SEGMENTS =
            List.of(".git", "target", "node_modules", "tasks", "dist", "coverage", ".idea");
    private static final List<String> EXCLUDED_PATH_FRAGMENTS =
            List.of("src/test/", "test/fixtures");

    private RemoteDebugExposureGuard() {
    }

    // ------------------------------------------------------------------
    // Compose policy
    // ------------------------------------------------------------------

    static List<String> composeViolations(String composeText, String sourceLabel) {
        List<String> violations = new ArrayList<>();
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(composeText);
        } catch (RuntimeException e) {
            violations.add(sourceLabel + ": compose content is not valid YAML (" + e.getClass().getSimpleName() + ")");
            return violations;
        }
        Map<Object, Object> root = asMap(loaded);
        if (root == null) {
            return violations;
        }
        Map<Object, Object> services = asMap(root.get("services"));
        if (services == null) {
            return violations;
        }
        Map<Object, Object> backend = asMap(services.get("backend"));
        if (backend == null) {
            return violations;
        }
        if (backend.containsKey("extends")) {
            violations.add(sourceLabel + ": backend must not use 'extends'; cross-file port merging cannot be "
                    + "verified structurally");
        }
        Object ports = backend.get("ports");
        if (ports == null) {
            return violations;
        }
        if (!(ports instanceof List<?> portList)) {
            violations.add(sourceLabel + ": backend 'ports' must be a list, found: " + ports);
            return violations;
        }
        for (Object entry : portList) {
            if (publishesDebugContainerPort(entry)) {
                violations.add(sourceLabel + ": backend publishes JDWP container port " + DEBUG_PORT
                        + ": " + describePort(entry));
            }
        }
        return violations;
    }

    private static boolean publishesDebugContainerPort(Object entry) {
        if (entry instanceof Map<?, ?> mapping) {
            Map<Object, Object> normalized = asMap(mapping);
            Object target = normalized.get("target");
            return portValueContainsDebugPort(target);
        }
        if (entry instanceof Number number) {
            return number.longValue() == DEBUG_PORT;
        }
        if (entry instanceof String text) {
            return portValueContainsDebugPort(shortFormContainerPort(text));
        }
        return false;
    }

    /**
     * Normalizes the container/target side of a Compose short port spec, which
     * may be {@code "5005"}, {@code "15005:5005"}, {@code "127.0.0.1:15005:5005"}
     * or an IPv6 form such as {@code "[::1]:15005:5005"}.
     */
    private static String shortFormContainerPort(String spec) {
        String text = spec.trim();
        int slash = text.indexOf('/');
        if (slash >= 0) {
            text = text.substring(0, slash);
        }
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close >= 0) {
                text = text.substring(close + 1);
                if (text.startsWith(":")) {
                    text = text.substring(1);
                }
            }
        }
        int lastColon = text.lastIndexOf(':');
        if (lastColon >= 0) {
            text = text.substring(lastColon + 1);
        }
        return text.trim();
    }

    private static boolean portValueContainsDebugPort(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Number number) {
            return number.longValue() == DEBUG_PORT;
        }
        String text = String.valueOf(value).trim();
        int slash = text.indexOf('/');
        if (slash >= 0) {
            text = text.substring(0, slash);
        }
        if (text.isEmpty()) {
            return false;
        }
        int dash = text.indexOf('-');
        if (dash > 0) {
            Integer start = parseInt(text.substring(0, dash));
            Integer end = parseInt(text.substring(dash + 1));
            return start != null && end != null && start <= DEBUG_PORT && DEBUG_PORT <= end;
        }
        Integer single = parseInt(text);
        return single != null && single == DEBUG_PORT;
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String describePort(Object entry) {
        if (entry instanceof Map<?, ?> mapping) {
            return "target=" + mapping.get("target") + ", published=" + mapping.get("published")
                    + ", protocol=" + mapping.get("protocol");
        }
        return String.valueOf(entry);
    }

    // ------------------------------------------------------------------
    // Repository file policy
    // ------------------------------------------------------------------

    static List<Path> runtimeConfigurationFiles(Path root) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        List<Path> result = new ArrayList<>();

        List<Path> tracked = gitTrackedFiles(normalizedRoot);
        if (tracked != null) {
            for (Path relative : tracked) {
                if (!isRuntimeConfiguration(relative) || isExcluded(relative)) {
                    continue;
                }
                Path absolute = normalizedRoot.resolve(relative).normalize();
                if (absolute.startsWith(normalizedRoot) && Files.isRegularFile(absolute)) {
                    result.add(absolute);
                }
            }
            return result;
        }

        try (Stream<Path> stream = Files.walk(normalizedRoot, MAX_WALK_DEPTH)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !isExcluded(normalizedRoot.relativize(path)))
                    .filter(path -> isRuntimeConfiguration(normalizedRoot.relativize(path)))
                    .forEach(result::add);
        }
        return result;
    }

    static List<String> repositoryDebugViolations(Path root) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : runtimeConfigurationFiles(root)) {
            String relative = relativePath(root, file);
            String name = file.getFileName().toString();

            if (name.endsWith(".yml") || name.endsWith(".yaml")) {
                if (COMPOSE_FILE.matcher(name).matches()) {
                    String content = readSafely(file);
                    if (content != null) {
                        violations.addAll(composeViolations(content, relative));
                    }
                    continue;
                }
            }

            if (name.equals("pom.xml")) {
                String content = readSafely(file);
                Document document = parseXml(content);
                if (document == null) {
                    violations.add(relative + ": pom.xml could not be parsed as XML");
                } else {
                    violations.addAll(mavenPomViolations(document, relative));
                }
                continue;
            }

            String content = readSafely(file);
            if (content == null) {
                continue;
            }
            if (containsDebugArgument(content)) {
                violations.add(relative + ": enables a Java debug agent in a default launch surface ("
                        + describeDebugArgument(content) + ")");
            }
        }
        return violations;
    }

    private static boolean isRuntimeConfiguration(Path relative) {
        String path = relative.toString().replace('\\', '/');
        String name = relative.getFileName().toString();

        if (name.equals("pom.xml")) {
            return true;
        }
        if (path.endsWith(".mvn/jvm.config") || path.endsWith(".mvn/maven.config")) {
            return true;
        }
        if (name.equals("maven-wrapper.properties") && path.contains(".mvn/")) {
            return true;
        }
        if (name.equals("mvnw") || name.equals("mvnw.cmd")) {
            return true;
        }
        if (name.startsWith("Dockerfile")) {
            return true;
        }
        if (COMPOSE_FILE.matcher(name).matches()) {
            return true;
        }
        if (name.endsWith(".sh") || name.endsWith(".bash") || name.endsWith(".ps1")
                || name.endsWith(".cmd") || name.endsWith(".bat")) {
            return true;
        }
        if (isCiOrDeploymentManifest(path)) {
            return true;
        }
        if (name.startsWith("application")
                && (name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml"))) {
            return true;
        }
        return false;
    }

    private static boolean isCiOrDeploymentManifest(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.equals("Jenkinsfile") || name.equals("Jenkinsfile.groovy")) {
            return true;
        }
        if (name.equals(".gitlab-ci.yml") || name.equals(".gitlab-ci.yaml")
                || name.equals("azure-pipelines.yml") || name.equals("azure-pipelines.yaml")
                || name.equals(".travis.yml") || name.equals("bitbucket-pipelines.yml")
                || name.equals("appveyor.yml") || name.equals("appveyor.yaml")) {
            return true;
        }
        if (path.startsWith(".github/workflows/") && CI_YAML.matcher(name).matches()) {
            return true;
        }
        if (path.startsWith(".circleci/") || path.startsWith(".buildkite/")
                || path.startsWith(".teamcity/") || path.startsWith("deploy/")
                || path.startsWith("deployment/") || path.startsWith(".azure/")) {
            return true;
        }
        return false;
    }

    private static boolean isExcluded(Path relative) {
        String path = relative.toString().replace('\\', '/');
        for (String segment : path.split("/")) {
            if (EXCLUDED_SEGMENTS.contains(segment)) {
                return true;
            }
        }
        for (String fragment : EXCLUDED_PATH_FRAGMENTS) {
            if (path.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> gitTrackedFiles(Path root) {
        if (!Files.exists(root.resolve(".git"))) {
            return null;
        }
        Process process = null;
        try {
            process = new ProcessBuilder("git", "-C", root.toString(), "ls-files", "-z")
                    .redirectErrorStream(true)
                    .start();
            byte[] output = process.getInputStream().readAllBytes();
            int exit = process.waitFor();
            if (exit != 0) {
                return null;
            }
            String text = new String(output, StandardCharsets.UTF_8);
            List<Path> files = new ArrayList<>();
            for (String entry : text.split("\0")) {
                if (!entry.isEmpty()) {
                    files.add(Path.of(entry));
                }
            }
            return files;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    // ------------------------------------------------------------------
    // Maven policy
    // ------------------------------------------------------------------

    static List<String> mavenPomViolations(Document document, String sourceLabel) {
        List<String> violations = new ArrayList<>();
        Element root = document.getDocumentElement();

        Element build = directChild(root, "build");
        if (build != null && containsDebugArgument(build.getTextContent())) {
            violations.add(sourceLabel + ": default <build> enables a debug agent: "
                    + describeDebugArgument(build.getTextContent()));
        }

        Element profiles = directChild(root, "profiles");
        if (profiles != null) {
            for (Element profile : directChildren(profiles, "profile")) {
                String id = textOfDirectChild(profile, "id");
                String profileText = profile.getTextContent();
                if (!containsDebugArgument(profileText)) {
                    continue;
                }
                if (LOCAL_DEBUG_PROFILE_ID.equals(id)) {
                    if (hasAutomaticActivation(profile)) {
                        violations.add(sourceLabel + ": profile '" + LOCAL_DEBUG_PROFILE_ID
                                + "' must not declare automatic activation");
                    } else if (!isAllowedLocalDebugConfiguration(profileText)) {
                        violations.add(sourceLabel + ": profile '" + LOCAL_DEBUG_PROFILE_ID
                                + "' JDWP configuration is not the reviewed loopback-only argument");
                    }
                } else {
                    violations.add(sourceLabel + ": profile '" + id
                            + "' enables a debug agent; only the inactive '" + LOCAL_DEBUG_PROFILE_ID
                            + "' profile may do so");
                }
            }
        }
        return violations;
    }

    static boolean isAllowedLocalDebugConfiguration(String configuration) {
        if (configuration == null || configuration.isEmpty()) {
            return false;
        }
        if (countOccurrences(configuration, "-agentlib:jdwp") != 1) {
            return false;
        }
        if (countOccurrences(configuration, "-Xrunjdwp") != 0
                || countOccurrences(configuration, "-Xdebug") != 0) {
            return false;
        }
        return ALLOWED_LOCAL_DEBUG_ADDRESS.equals(extractJdwpAddress(configuration));
    }

    static boolean containsDebugArgument(String text) {
        return text != null && !text.isEmpty() && DEBUG_MARKER.matcher(text).find();
    }

    private static String describeDebugArgument(String text) {
        String address = extractJdwpAddress(text);
        if (address != null) {
            return "JDWP agent bound to '" + address + "'";
        }
        return "legacy debug flag";
    }

    static Element findDefaultSpringBootPlugin(Document document) {
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

    static Element findProfileById(Document document, String id) {
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

    static boolean hasAutomaticActivation(Element profile) {
        Element activation = directChild(profile, "activation");
        if (activation == null) {
            return false;
        }
        if (directChild(activation, "activeByDefault") != null) {
            return true;
        }
        return directChild(activation, "os") != null
                || directChild(activation, "jdk") != null
                || directChild(activation, "property") != null
                || directChild(activation, "file") != null;
    }

    static Element findSpringBootPluginUnder(Element scope) {
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

    static String pluginConfigurationText(Element plugin) {
        Element configuration = directChild(plugin, "configuration");
        if (configuration == null) {
            return "";
        }
        return configuration.getTextContent() == null ? "" : configuration.getTextContent();
    }

    static String extractJdwpAddress(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = JDWP_ADDRESS.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // XML / generic helpers
    // ------------------------------------------------------------------

    static Element directChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(node.getLocalName())) {
                return (Element) node;
            }
        }
        return null;
    }

    static List<Element> directChildren(Element parent, String localName) {
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

    static String textOfDirectChild(Element parent, String localName) {
        Element child = directChild(parent, localName);
        if (child == null || child.getTextContent() == null) {
            return null;
        }
        return child.getTextContent().trim();
    }

    static int countOccurrences(String haystack, String needle) {
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

    static String relativePath(Path root, Path file) {
        try {
            return root.relativize(file).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return file.toString();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Object merge = map.get("<<");
        if (merge == null) {
            return (Map<Object, Object>) map;
        }
        Map<Object, Object> merged = new LinkedHashMap<>();
        if (merge instanceof Map<?, ?> mergeMap) {
            merged.putAll((Map<Object, Object>) mergeMap);
        } else if (merge instanceof List<?> mergeList) {
            for (Object candidate : mergeList) {
                if (candidate instanceof Map<?, ?> mergeMap) {
                    merged.putAll((Map<Object, Object>) mergeMap);
                }
            }
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!"<<".equals(entry.getKey())) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    private static Document parseXml(String content) {
        if (content == null) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(content)));
        } catch (Exception e) {
            return null;
        }
    }

    private static String readSafely(Path file) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            for (byte value : bytes) {
                if (value == 0) {
                    return null;
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
