package com.wish.rd.bootstrap.evaluation.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.evaluation.EvaluationProperties;
import com.wish.rd.engine.evaluation.CodingBenchmarkCaseRuntime;
import com.wish.rd.engine.evaluation.CodingBenchmarkRuntimeCatalogPort;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkCase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Builds {@link CodingBenchmarkCaseRuntime} descriptors from frozen snapshot manifests and the
 * on-disk prepared-case layout.
 */
@Component
public final class CodingBenchmarkRuntimeCatalogAdapter implements CodingBenchmarkRuntimeCatalogPort {

    private static final String SNAPSHOT_ID_PATTERN = "^[A-Za-z0-9._-]{1,200}$";
    private static final String ORACLE_SCRIPT = "/opt/rd-pi-bridge/rd_eval_oracle.py";

    private final Path prepRoot;
    private final Path snapshotRoot;
    private final Path templatePath;
    private final String fallbackOracleImage;
    private final ObjectMapper mapper;
    private final Map<String, RuntimeCaseDescriptor> descriptors;

    @Autowired
    public CodingBenchmarkRuntimeCatalogAdapter(EvaluationProperties properties, ObjectMapper mapper) {
        this(Objects.requireNonNull(properties, "properties must not be null").resolvedCodingBenchmarkPrepRoot(),
                properties.resolvedCodingBenchmarkRoot(),
                properties.getRepositoryRoot().resolve("scripts/evaluation/coding-benchmark-v2.template.json"),
                properties.getCodingBenchmark().resolvedOracleImage(),
                Objects.requireNonNull(mapper, "mapper must not be null"));
    }

    CodingBenchmarkRuntimeCatalogAdapter(
            Path prepRoot,
            Path snapshotRoot,
            Path templatePath,
            String fallbackOracleImage,
            ObjectMapper mapper
    ) {
        this.prepRoot = prepRoot.toAbsolutePath().normalize();
        this.snapshotRoot = snapshotRoot.toAbsolutePath().normalize();
        this.templatePath = templatePath.toAbsolutePath().normalize();
        this.fallbackOracleImage = fallbackOracleImage == null ? "" : fallbackOracleImage.strip();
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.descriptors = new HashMap<>();
    }

    @Override
    public List<CodingBenchmarkCaseRuntime> runtimeForSnapshot(String snapshotId) {
        if (!snapshotId.matches(SNAPSHOT_ID_PATTERN)) {
            throw new IllegalArgumentException("snapshotId contains unsupported characters");
        }
        Path snapshotDir = snapshotRoot.resolve(snapshotId).normalize();
        if (!snapshotDir.startsWith(snapshotRoot)) {
            throw new IllegalArgumentException("snapshotId resolves outside the configured snapshot root");
        }

        JsonNode dataset = readManifest(snapshotDir, "dataset-manifest.json");
        JsonNode environment = readManifest(snapshotDir, "environment-manifest.json");
        JsonNode runtimeSidecar = readManifest(snapshotDir, "runtime-manifest.json");

        List<String> images = extractImages(environment);
        String agentImage = findImage(images, "coding-eval-agent")
                .orElseThrow(() -> new IllegalArgumentException("environment manifest lacks an agent image"));
        String thinOracleImage = findImage(images, "coding-eval-oracle")
                .orElseGet(this::requireFallbackOracleImage);

        Map<String, RuntimeCaseDescriptor> parsed = parseRuntimeManifest(runtimeSidecar, agentImage, thinOracleImage);
        RuntimeCaseDescriptor defaultDescriptor = parsed.remove("__default__");
        Map<String, List<String>> oracleCommandsByCase = loadOracleCommandsByCase();

        List<CodingBenchmarkCaseRuntime> result = new ArrayList<>();
        for (JsonNode caseNode : dataset.required("cases")) {
            String caseId = caseNode.required("caseId").asText();
            String language = caseNode.path("language").asText("");
            RuntimeCaseDescriptor desc = parsed.getOrDefault(caseId, defaultDescriptor);
            CaseAssets assets = resolveCaseAssets(caseId);
            List<String> oracleCommand = usesFrozenOracleCommand(desc, defaultDescriptor)
                    ? desc.oracleCommand
                    : buildOracleCommand(caseId, assets, oracleCommandsByCase.get(caseId));
            String oracleImage = resolveOracleImage(language, caseId, images, thinOracleImage);
            if (desc.oracleImage != null
                    && !desc.oracleImage.equals(defaultDescriptor.oracleImage())
                    && !desc.oracleImage.equals(thinOracleImage)) {
                oracleImage = desc.oracleImage;
            }

            result.add(CodingBenchmarkCaseRuntime.of(
                    prepRoot,
                    caseId,
                    desc.agentImage != null ? desc.agentImage : agentImage,
                    oracleImage,
                    assets.repoPath(),
                    assets.cachePath(),
                    assets.repoPath(),
                    assets.cachePath(),
                    assets.bundlePath(),
                    assets.patchPath(),
                    desc.protectedTarget,
                    desc.agentCommand,
                    oracleCommand,
                    desc.agentTimeoutMillis,
                    desc.oracleTimeoutMillis));
        }
        return List.copyOf(result);
    }

    /**
     * Thin {@code coding-eval-oracle} images carry the verifier script but not language toolchains.
     * Prefer the attested java/node layer from the environment manifest so {@code mvn}/{@code npm}
     * exist offline; the executor bind-mounts {@code rd_eval_oracle.py} into that image.
     */
    static String resolveOracleImage(
            String language,
            String caseId,
            List<String> images,
            String thinOracleImage
    ) {
        String lang = language == null ? "" : language.trim().toUpperCase(Locale.ROOT);
        if ("JAVA".equals(lang)) {
            if (caseId != null && caseId.contains("mockito")) {
                return findImage(images, "coding-eval-java17")
                        .or(() -> findImage(images, "coding-eval-java21"))
                        .orElse(thinOracleImage);
            }
            return findImage(images, "coding-eval-java21")
                    .or(() -> findImage(images, "coding-eval-java17"))
                    .orElse(thinOracleImage);
        }
        if ("TSJS".equals(lang) || "JAVASCRIPT".equals(lang) || "TYPESCRIPT".equals(lang)) {
            return findImage(images, "coding-eval-node")
                    .or(() -> findImage(images, "coding-eval-agent"))
                    .orElse(thinOracleImage);
        }
        return thinOracleImage;
    }

    private String requireFallbackOracleImage() {
        if (fallbackOracleImage.isBlank()) {
            throw new IllegalArgumentException(
                    "environment manifest lacks an oracle image and rd.evaluation.coding-benchmark.oracle-image is unset");
        }
        return fallbackOracleImage;
    }

    private static Optional<String> findImage(List<String> images, String roleMarker) {
        return images.stream()
                .filter(image -> image.toLowerCase().contains(roleMarker))
                .findFirst();
    }

    private CaseAssets resolveCaseAssets(String caseId) {
        Path repoPath = prepRoot.resolve("prepared").resolve(caseId);
        List<Path> cacheCandidates = new ArrayList<>();
        if (caseId.startsWith("rd-bot--")) {
            // Prefer the shared warmed Maven cache first. Per-case entries are often
            // relative symlinks (cache-rd-bot-* -> cache-m2-rdbot); copying those as
            // links into the trial workspace leaves dangling mounts and INFRA_ERROR.
            cacheCandidates.add(prepRoot.resolve("cache-m2-rdbot"));
        }
        cacheCandidates.add(prepRoot.resolve("cache").resolve(caseId));
        cacheCandidates.add(prepRoot.resolve("cache-" + caseId));
        Path cachePath = firstExisting(cacheCandidates.toArray(Path[]::new));
        Path bundlePath = Files.exists(prepRoot.resolve("bundles").resolve(caseId + ".tar.gz"))
                ? prepRoot.resolve("bundles").resolve(caseId + ".tar.gz")
                : null;
        Path patchPath = Files.exists(prepRoot.resolve("assets").resolve(caseId).resolve("runtime-withheld.patch"))
                ? prepRoot.resolve("assets").resolve(caseId).resolve("runtime-withheld.patch")
                : null;
        return new CaseAssets(repoPath, cachePath, bundlePath, patchPath);
    }

    private List<String> buildOracleCommand(String caseId, CaseAssets assets, List<String> testCommand) {
        if (testCommand == null || testCommand.isEmpty()) {
            throw new IllegalArgumentException("oracle test command is unavailable for case " + caseId);
        }
        JsonNode contract = readOracleContract(caseId);
        List<String> expectedTestIds = expectedTestIds(contract);
        List<String> rewrittenTestCommand = rewriteOracleTestSelector(testCommand, expectedTestIds);
        boolean mavenHarness = rewrittenTestCommand.stream().anyMatch(arg -> arg.equals("mvn") || arg.endsWith("/mvn"));
        List<String> command = new ArrayList<>();
        command.add("python3");
        command.add(ORACLE_SCRIPT);
        command.add("--verifier-repository");
        command.add("/work/verifier");
        command.add("--patch");
        command.add("/input/candidate.patch");
        if (assets.patchPath() != null) {
            command.add("--protected-test-patch");
            command.add("/input/runtime-withheld.patch");
        } else {
            command.add("--protected-bundle");
            command.add("/input/protected-tests");
            command.add("--protected-target");
            command.add("/work/verifier");
        }
        for (String testId : expectedTestIds) {
            command.add("--expected-test-id");
            command.add(testId);
        }
        try {
            command.add("--command-json");
            command.add(mapper.writeValueAsString(rewrittenTestCommand));
        } catch (IOException exception) {
            throw new IllegalArgumentException("failed to serialise oracle command for case " + caseId, exception);
        }
        command.add("--network-mode");
        command.add("none");
        if (mavenHarness) {
            // Surefire does not emit RD_EVAL_COLLECTED_TEST_IDS; host selects failToPass via -Dtest=.
            command.add("--result-parser");
            command.add("EXPECTED_IDS");
        }
        command.add("--output");
        command.add("/work/output/oracle-result.json");
        return List.copyOf(command);
    }

    /**
     * Templates still ship {@code -Dtest=AgentBenchmarkOracleTest} as a placeholder. Replace it with
     * the contract's failToPass selectors and require at least one matching test to execute.
     */
    static List<String> rewriteOracleTestSelector(List<String> testCommand, List<String> expectedTestIds) {
        if (testCommand == null || testCommand.isEmpty() || expectedTestIds == null || expectedTestIds.isEmpty()) {
            return testCommand == null ? List.of() : List.copyOf(testCommand);
        }
        String selector = String.join(",", expectedTestIds);
        List<String> rewritten = new ArrayList<>(testCommand.size());
        boolean replacedSelector = false;
        boolean maven = testCommand.stream().anyMatch(arg -> arg.equals("mvn") || arg.endsWith("/mvn"));
        boolean hasOffline = testCommand.contains("-o") || testCommand.contains("--offline");
        for (String arg : testCommand) {
            if (arg.startsWith("-Dtest=") && arg.contains("AgentBenchmarkOracleTest")) {
                rewritten.add("-Dtest=" + selector);
                replacedSelector = true;
            } else if ("-DfailIfNoTests=false".equals(arg) || "-Dsurefire.failIfNoTests=false".equals(arg)) {
                rewritten.add(arg.replace("=false", "=true"));
            } else if ("-q".equals(arg) || "--quiet".equals(arg)) {
                // keep oracle logs available when diagnosing TEST_FAIL
                continue;
            } else {
                rewritten.add(arg);
            }
        }
        if (maven && !hasOffline) {
            int insertAt = 1;
            for (int index = 0; index < rewritten.size(); index++) {
                String arg = rewritten.get(index);
                if (arg.equals("mvn") || arg.endsWith("/mvn")) {
                    insertAt = index + 1;
                    break;
                }
            }
            rewritten.add(insertAt, "-o");
        }
        if (!replacedSelector && maven) {
            return List.copyOf(rewritten);
        }
        return List.copyOf(rewritten);
    }

    private JsonNode readOracleContract(String caseId) {
        Path contractPath = prepRoot.resolve("assets").resolve(caseId).resolve("oracle-contract.json");
        if (!Files.isRegularFile(contractPath)) {
            throw new IllegalArgumentException("oracle contract is unavailable for case " + caseId);
        }
        try {
            return mapper.readTree(Files.readAllBytes(contractPath));
        } catch (IOException exception) {
            throw new IllegalArgumentException("failed to read oracle contract for case " + caseId, exception);
        }
    }

    private static List<String> expectedTestIds(JsonNode contract) {
        JsonNode expected = contract.path("expectedTests");
        List<String> ids = new ArrayList<>();
        // Score only fail-to-pass (and new-to-pass when no f2p). SWE metadata often dumps hundreds of
        // unrelated newToPass class names that Maven never emits as RD_EVAL_COLLECTED_TEST_IDS.
        appendTestIds(ids, expected.path("failToPass"));
        if (ids.isEmpty()) {
            appendTestIds(ids, expected.path("newToPass"));
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("oracle contract does not declare expected tests");
        }
        return List.copyOf(ids);
    }

    private static void appendTestIds(List<String> ids, JsonNode node) {
        if (!node.isArray()) {
            return;
        }
        for (JsonNode value : node) {
            ids.add(value.asText());
        }
    }

    private Map<String, List<String>> loadOracleCommandsByCase() {
        if (!Files.isRegularFile(templatePath)) {
            return Map.of();
        }
        try {
            JsonNode root = mapper.readTree(Files.readAllBytes(templatePath));
            Map<String, List<String>> commands = new LinkedHashMap<>();
            for (JsonNode caseNode : root.path("cases")) {
                String caseId = caseNode.path("caseId").asText(null);
                JsonNode oracleCommands = caseNode.path("oracleTestCommands");
                if (caseId != null && oracleCommands.isArray() && !oracleCommands.isEmpty()) {
                    List<String> argv = new ArrayList<>();
                    for (JsonNode entry : oracleCommands) {
                        argv.add(entry.asText());
                    }
                    commands.put(caseId, List.copyOf(argv));
                }
            }
            return commands;
        } catch (IOException exception) {
            throw new IllegalArgumentException("failed to read coding benchmark template: " + templatePath, exception);
        }
    }

    private static List<String> extractImages(JsonNode environment) {
        List<String> images = new ArrayList<>();
        for (JsonNode img : environment.required("images")) {
            images.add(img.asText());
        }
        return images;
    }

    private static boolean usesFrozenOracleCommand(RuntimeCaseDescriptor descriptor, RuntimeCaseDescriptor defaults) {
        return descriptor.oracleCommand != null && !descriptor.oracleCommand.equals(defaults.oracleCommand());
    }

    private Map<String, RuntimeCaseDescriptor> parseRuntimeManifest(
            JsonNode runtimeSidecar,
            String defaultAgent,
            String defaultOracle
    ) {
        Map<String, RuntimeCaseDescriptor> result = new HashMap<>();
        RuntimeCaseDescriptor defaultDescriptor = RuntimeCaseDescriptor.fromJson(runtimeSidecar, defaultAgent, defaultOracle);
        JsonNode cases = runtimeSidecar.path("cases");
        if (cases.isArray()) {
            for (JsonNode entry : cases) {
                String caseId = entry.path("caseId").asText(null);
                if (caseId != null) {
                    result.put(caseId, RuntimeCaseDescriptor.fromJson(entry, defaultAgent, defaultOracle));
                }
            }
        }
        result.put("__default__", defaultDescriptor);
        return result;
    }

    private JsonNode readManifest(Path dir, String name) {
        try {
            Path manifest = dir.resolve(name).normalize();
            if (!manifest.startsWith(dir)) {
                throw new IllegalArgumentException(name + " resolves outside snapshot directory");
            }
            if (Files.isSymbolicLink(manifest)) {
                throw new IllegalArgumentException(name + " must not be a symbolic link");
            }
            return mapper.readTree(Files.readAllBytes(manifest));
        } catch (IOException exception) {
            throw new IllegalArgumentException("failed to read " + name + ": " + exception.getMessage(), exception);
        }
    }

    private static Path firstExisting(Path... candidates) {
        for (Path candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (Files.isDirectory(candidate) && directoryHasEntries(candidate)) {
                try {
                    // Symlinked caches (e.g. cache-rd-bot-* -> cache-m2-rdbot) must be
                    // materialised from the real directory; copying the symlink itself breaks mounts.
                    return candidate.toRealPath();
                } catch (IOException exception) {
                    return candidate.toAbsolutePath().normalize();
                }
            }
        }
        throw new IllegalArgumentException("case cache directory is unavailable");
    }

    private static boolean directoryHasEntries(Path directory) {
        try (var stream = Files.list(directory)) {
            return stream.findAny().isPresent();
        } catch (IOException exception) {
            return false;
        }
    }

    private record CaseAssets(Path repoPath, Path cachePath, Path bundlePath, Path patchPath) {}

    private record RuntimeCaseDescriptor(
            String agentImage,
            String oracleImage,
            String protectedTarget,
            List<String> agentCommand,
            List<String> oracleCommand,
            long agentTimeoutMillis,
            long oracleTimeoutMillis
    ) {
        static final long DEFAULT_AGENT_TIMEOUT = 30 * 60 * 1000L;
        static final long DEFAULT_ORACLE_TIMEOUT = 5 * 60 * 1000L;
        static final List<String> DEFAULT_AGENT_CMD = List.of(
                "node", "/work/pi-agent/rd-pi-bridge.mjs", "--request-path", "/work/input/request.json");
        static final List<String> DEFAULT_ORACLE_CMD = List.of("python3", ORACLE_SCRIPT);

        static RuntimeCaseDescriptor defaults(String defaultAgent, String defaultOracle) {
            return new RuntimeCaseDescriptor(
                    defaultAgent, defaultOracle, "", DEFAULT_AGENT_CMD, DEFAULT_ORACLE_CMD,
                    DEFAULT_AGENT_TIMEOUT, DEFAULT_ORACLE_TIMEOUT);
        }

        static RuntimeCaseDescriptor fromJson(JsonNode node, String defaultAgent, String defaultOracle) {
            long agentTimeout = node.path("defaultAgentTimeoutMillis").asLong(DEFAULT_AGENT_TIMEOUT);
            long oracleTimeout = node.path("defaultOracleTimeoutMillis").asLong(DEFAULT_ORACLE_TIMEOUT);
            if (node.has("agentTimeoutMillis")) {
                agentTimeout = node.path("agentTimeoutMillis").asLong(agentTimeout);
            }
            if (node.has("oracleTimeoutMillis")) {
                oracleTimeout = node.path("oracleTimeoutMillis").asLong(oracleTimeout);
            }
            return new RuntimeCaseDescriptor(
                    nullOrText(node.path("agentImage"), defaultAgent),
                    nullOrText(node.path("oracleImage"), defaultOracle),
                    node.path("protectedTarget").asText(""),
                    readStringList(node.path("agentCommand"), readStringList(node.path("defaultAgentCommand"), DEFAULT_AGENT_CMD)),
                    readStringList(node.path("oracleCommand"), readStringList(node.path("defaultOracleCommand"), DEFAULT_ORACLE_CMD)),
                    Math.max(1, agentTimeout),
                    Math.max(1, oracleTimeout));
        }

        private static String nullOrText(JsonNode node, String fallback) {
            return node.isMissingNode() || node.isNull() ? fallback : node.asText();
        }

        private static List<String> readStringList(JsonNode node, List<String> fallback) {
            if (!node.isArray()) {
                return fallback;
            }
            List<String> result = new ArrayList<>();
            for (JsonNode item : node) {
                result.add(item.asText());
            }
            return result.isEmpty() ? fallback : List.copyOf(result);
        }
    }
}
