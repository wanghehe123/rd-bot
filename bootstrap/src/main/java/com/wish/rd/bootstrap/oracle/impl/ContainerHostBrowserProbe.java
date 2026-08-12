package com.wish.rd.bootstrap.oracle.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.oracle.HostBrowserProbe;
import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionType;
import com.wish.rd.exec.repair.docker.ContainerRunnerPort;
import com.wish.rd.exec.repair.docker.model.ContainerRunRequest;
import com.wish.rd.exec.repair.docker.model.ContainerRunResult;
import com.wish.rd.exec.repair.docker.model.ContainerSecurityPolicy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Host-owned browser probe backed by the fixed Playwright helper in the Pi QA image.
 * The Host supplies the base URL, selector, route, timeout, image, mounts, and output directory;
 * no agent output is used to construct the container request.
 */
public final class ContainerHostBrowserProbe implements HostBrowserProbe {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CONTAINER_REPOSITORY = "/work/repo:ro";
    private static final String CONTAINER_OUTPUT = "/work/output";
    private static final String RESULT_FILE = "result.json";
    private static final long MAX_BASE_URL_CHARS = 2_048L;
    private static final int MAX_ROUTE_CHARS = 512;
    private static final int MAX_ARIA_VALUE_CHARS = 2_048;
    private static final Pattern ARIA_ATTRIBUTE = Pattern.compile("aria-[a-z][a-z0-9-]{0,62}");
    private static final Pattern SUSPICIOUS_SELECTOR_SCHEME = Pattern.compile("(?i)^(?:javascript|data|file):");
    private static final ContainerSecurityPolicy SECURITY_POLICY = new ContainerSecurityPolicy(
            true,
            true,
            true,
            true,
            "1g",
            "1",
            128,
            "node",
            Map.of("/tmp", "rw,nosuid,nodev,noexec,size=64m")
    );

    private final ContainerRunnerPort containerRunner;
    private final Configuration configuration;

    /** Creates a concrete Host probe with a typed Docker container runner. */
    public ContainerHostBrowserProbe(ContainerRunnerPort containerRunner, Configuration configuration) {
        this.containerRunner = Objects.requireNonNull(containerRunner, "containerRunner must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
    }

    /** Compatibility path for older callers that only supplied a selector. */
    @Override
    public BrowserAssertionSnapshot inspect(String target, AssertionEvaluationContext context) throws Exception {
        return inspect(new BrowserProbeRequest(
                AssertionType.BROWSER_DOM,
                target,
                "",
                configuration.defaultTimeoutMillis()
        ), context);
    }

    /** Runs the fixed in-image Playwright helper with a bounded, Host-built request. */
    @Override
    public BrowserAssertionSnapshot inspect(BrowserProbeRequest request, AssertionEvaluationContext context)
            throws Exception {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "context must not be null");
        BrowserOperation operation = operation(request);
        URI baseUrl = baseUrl(context.baseUrl());
        Path workspace = workspace(context.workspaceRoot());
        long timeoutMillis = effectiveTimeout(request.timeoutMillis());
        Path outputDirectory = createOutputDirectory();
        try {
            ContainerRunRequest containerRequest = containerRequest(
                    operation,
                    baseUrl,
                    workspace,
                    outputDirectory,
                    timeoutMillis
            );
            ContainerRunResult result = containerRunner.run(containerRequest);
            if (result == null) {
                throw new IllegalStateException("Host browser probe container returned no result");
            }
            if (result.exitCode() == 124) {
                throw new IllegalStateException("Host browser probe timed out after " + timeoutMillis + "ms");
            }
            if (result.exitCode() != 0) {
                throw new IllegalStateException("Host browser probe container exited with code " + result.exitCode());
            }
            return readSnapshot(result, outputDirectory, operation);
        } catch (IOException exception) {
            throw new IllegalStateException("Host browser probe container failed", exception);
        } finally {
            deleteOutputDirectory(outputDirectory);
        }
    }

    private ContainerRunRequest containerRequest(
            BrowserOperation operation,
            URI baseUrl,
            Path workspace,
            Path outputDirectory,
            long timeoutMillis
    ) {
        Map<String, String> mounts = new LinkedHashMap<>();
        mounts.put(workspace.toString(), CONTAINER_REPOSITORY);
        mounts.put(outputDirectory.toString(), CONTAINER_OUTPUT);
        List<String> command = new ArrayList<>();
        command.add("host-browser-probe");
        command.add("--kind");
        command.add(operation.type().name());
        command.add("--base-url");
        command.add(baseUrl.toString());
        command.add("--timeout-millis");
        command.add(String.valueOf(timeoutMillis));
        command.add("--output-path");
        command.add(CONTAINER_OUTPUT + "/" + RESULT_FILE);
        if (operation.type() == AssertionType.BROWSER_ROUTE) {
            if (!operation.routePath().isBlank()) {
                command.add("--route-path");
                command.add(operation.routePath());
            }
        } else {
            command.add("--selector");
            command.add(operation.selector());
        }
        if (!operation.ariaAttribute().isBlank()) {
            command.add("--aria-attribute");
            command.add(operation.ariaAttribute());
        }
        return new ContainerRunRequest(
                "rd-host-browser-" + UUID.randomUUID(),
                configuration.image(),
                List.copyOf(command),
                Map.of("HOME", "/tmp"),
                Map.copyOf(mounts),
                "/work/repo",
                configuration.networkMode(),
                true,
                false,
                outputDirectory,
                true,
                "1g",
                timeoutMillis,
                SECURITY_POLICY
        );
    }

    private BrowserAssertionSnapshot readSnapshot(
            ContainerRunResult result,
            Path outputDirectory,
            BrowserOperation operation
    ) throws IOException {
        Path resultPath = result.resultJson() == null
                ? outputDirectory.resolve(RESULT_FILE)
                : result.resultJson();
        resultPath = resultPath.toAbsolutePath().normalize();
        if (!resultPath.startsWith(outputDirectory)) {
            throw new IllegalStateException("Host browser probe result must remain under the Host output directory");
        }
        if (!Files.isRegularFile(resultPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Host browser probe did not write " + RESULT_FILE);
        }
        long bytes = Files.size(resultPath);
        if (bytes > configuration.maximumOutputBytes()) {
            throw new IllegalStateException("Host browser probe output exceeds Host output limit");
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(Files.readString(resultPath, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Host browser probe output is not valid JSON", exception);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("Host browser probe output must be a JSON object");
        }
        validateSnapshotFields(root);
        if (root.path("version").asInt(-1) != 1) {
            throw new IllegalStateException("Host browser probe output has an unsupported version");
        }
        if (!root.path("exists").isBoolean() || !root.path("visible").isBoolean()) {
            throw new IllegalStateException("Host browser probe output must include boolean exists and visible fields");
        }
        boolean exists = root.path("exists").asBoolean();
        boolean visible = root.path("visible").asBoolean();
        if (visible && !exists) {
            throw new IllegalStateException("Host browser probe output cannot report a visible missing target");
        }
        String route = boundedText(root.path("route"), "route", MAX_ROUTE_CHARS);
        Map<String, String> ariaAttributes = ariaAttributes(root.path("ariaAttributes"), operation.ariaAttribute());
        return new BrowserAssertionSnapshot(exists, visible, route, ariaAttributes);
    }

    private static void validateSnapshotFields(JsonNode root) {
        root.fieldNames().forEachRemaining(field -> {
            if (!"version".equals(field)
                    && !"exists".equals(field)
                    && !"visible".equals(field)
                    && !"route".equals(field)
                    && !"ariaAttributes".equals(field)) {
                throw new IllegalStateException("Host browser probe output contains an unsupported field");
            }
        });
    }

    private static String boundedText(JsonNode node, String field, int maximumChars) {
        if (node == null || !node.isTextual()) {
            throw new IllegalStateException("Host browser probe output " + field + " must be text");
        }
        String value = node.asText("");
        if (value.length() > maximumChars || containsControlCharacter(value)) {
            throw new IllegalStateException("Host browser probe output " + field + " exceeds Host limits");
        }
        return value;
    }

    private static Map<String, String> ariaAttributes(JsonNode node, String requestedAttribute) {
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("Host browser probe output ariaAttributes must be an object");
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            String name = entry.getKey() == null ? "" : entry.getKey().strip().toLowerCase(Locale.ROOT);
            if (!ARIA_ATTRIBUTE.matcher(name).matches() || !entry.getValue().isTextual()) {
                throw new IllegalStateException("Host browser probe output contains an invalid ARIA attribute");
            }
            String value = entry.getValue().asText("");
            if (value.length() > MAX_ARIA_VALUE_CHARS || containsControlCharacter(value)) {
                throw new IllegalStateException("Host browser probe output ARIA attribute exceeds Host limits");
            }
            attributes.put(name, value);
        });
        if (!requestedAttribute.isBlank() && attributes.keySet().stream().anyMatch(name -> !name.equals(requestedAttribute))) {
            throw new IllegalStateException("Host browser probe output returned an unrequested ARIA attribute");
        }
        if (!requestedAttribute.isBlank() && !attributes.containsKey(requestedAttribute)) {
            attributes.put(requestedAttribute, "");
        }
        return Map.copyOf(attributes);
    }

    private BrowserOperation operation(BrowserProbeRequest request) {
        AssertionType type = request.assertionType();
        if (type != AssertionType.BROWSER_DOM
                && type != AssertionType.BROWSER_ARIA
                && type != AssertionType.BROWSER_VISIBLE
                && type != AssertionType.BROWSER_ROUTE) {
            throw new IllegalArgumentException("Host browser probe supports browser assertion types only");
        }
        if (type == AssertionType.BROWSER_ROUTE) {
            return new BrowserOperation(type, "", routePath(request.target()), "");
        }
        String selector = selector(request.target());
        String ariaAttribute = type == AssertionType.BROWSER_ARIA
                ? ariaAttribute(request.ariaAttribute())
                : "";
        return new BrowserOperation(type, selector, "", ariaAttribute);
    }

    private String selector(String value) {
        String selector = text(value);
        if (selector.isBlank()
                || selector.length() > configuration.maximumSelectorChars()
                || containsControlCharacter(selector)
                || SUSPICIOUS_SELECTOR_SCHEME.matcher(selector).find()) {
            throw new IllegalArgumentException("Host browser selector is invalid or exceeds the Host limit");
        }
        return selector;
    }

    private static String routePath(String value) {
        String route = text(value);
        if ("browser".equals(route)) {
            return "";
        }
        if (route.isBlank()
                || route.length() > MAX_ROUTE_CHARS
                || !route.startsWith("/")
                || route.startsWith("//")
                || route.contains("\\")
                || containsControlCharacter(route)) {
            throw new IllegalArgumentException("Host browser route target must be browser or a bounded absolute path");
        }
        URI parsed;
        try {
            parsed = URI.create(route);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Host browser route target is invalid", exception);
        }
        if (parsed.isAbsolute() || parsed.getRawAuthority() != null || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException("Host browser route target must not select another origin");
        }
        return route;
    }

    private static String ariaAttribute(String value) {
        String attribute = text(value).toLowerCase(Locale.ROOT);
        if (attribute.isBlank()) {
            attribute = "aria-label";
        }
        if (!ARIA_ATTRIBUTE.matcher(attribute).matches()) {
            throw new IllegalArgumentException("Host browser ARIA attribute is invalid");
        }
        return attribute;
    }

    private static URI baseUrl(String value) {
        String baseUrl = text(value);
        if (baseUrl.isBlank() || baseUrl.length() > MAX_BASE_URL_CHARS || containsControlCharacter(baseUrl)) {
            throw new IllegalArgumentException("Host browser base URL is invalid or exceeds the Host limit");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Host browser base URL is invalid", exception);
        }
        if (!uri.isAbsolute()
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                || uri.getPort() > 65_535) {
            throw new IllegalArgumentException("Host browser base URL must be an absolute credential-free HTTP URL");
        }
        return uri.normalize();
    }

    private long effectiveTimeout(long requestedTimeoutMillis) {
        long requested = requestedTimeoutMillis > 0L ? requestedTimeoutMillis : configuration.defaultTimeoutMillis();
        return Math.min(requested, configuration.maximumTimeoutMillis());
    }

    private static Path workspace(Path workspaceRoot) {
        try {
            if (workspaceRoot == null || !Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Host browser workspace must be an existing directory");
            }
            return workspaceRoot.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Host browser workspace cannot be resolved", exception);
        }
    }

    private Path createOutputDirectory() {
        try {
            Files.createDirectories(configuration.outputRoot());
            return Files.createTempDirectory(configuration.outputRoot(), "host-browser-").toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new IllegalStateException("Host browser output directory cannot be created", exception);
        }
    }

    private static void deleteOutputDirectory(Path outputDirectory) {
        if (outputDirectory == null || !Files.exists(outputDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(outputDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The result was already consumed; a later workspace cleanup can remove a stale temp directory.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup of a Host-created temporary directory.
        }
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }

    private record BrowserOperation(
            AssertionType type,
            String selector,
            String routePath,
            String ariaAttribute
    ) {
    }

    /** Immutable Host configuration for the fixed browser helper. */
    public record Configuration(
            String image,
            String networkMode,
            Path outputRoot,
            long defaultTimeoutMillis,
            long maximumTimeoutMillis,
            int maximumSelectorChars,
            long maximumOutputBytes
    ) {
        public Configuration {
            image = requireText(image, "image");
            networkMode = requireText(networkMode, "networkMode");
            outputRoot = Objects.requireNonNull(outputRoot, "outputRoot must not be null").toAbsolutePath().normalize();
            if (defaultTimeoutMillis <= 0L || maximumTimeoutMillis < defaultTimeoutMillis
                    || maximumTimeoutMillis > 120_000L) {
                throw new IllegalArgumentException("browser timeouts must be positive, ordered, and no greater than 120000ms");
            }
            if (maximumSelectorChars <= 0 || maximumSelectorChars > 2_048) {
                throw new IllegalArgumentException("maximumSelectorChars must be between 1 and 2048");
            }
            if (maximumOutputBytes < 128L || maximumOutputBytes > 1_048_576L) {
                throw new IllegalArgumentException("maximumOutputBytes must be between 128 and 1048576");
            }
        }

        /** Default bounded configuration for the Pi QA Playwright image. */
        public static Configuration defaults(String image, String networkMode, Path outputRoot) {
            return new Configuration(image, networkMode, outputRoot, 30_000L, 60_000L, 512, 64 * 1024L);
        }

        private static String requireText(String value, String field) {
            String normalized = value == null ? "" : value.strip();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return normalized;
        }
    }
}
