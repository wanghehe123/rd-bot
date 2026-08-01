package com.wish.rd.bootstrap.evaluation.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.evaluation.EvaluationProperties;
import com.wish.rd.engine.evaluation.CodingBenchmarkExecutionHooks;
import com.wish.rd.engine.evaluation.CodingBenchmarkExecutionPort;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionRequest;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionResult;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkRuntimeAttestation;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Builds isolated Docker argv for one prepared coding trial without reusing the general repair
 * executor's broader workspace contract.
 */
@Component
@ConditionalOnProperty(
        name = "rd.evaluation.coding-benchmark.executor",
        havingValue = "docker",
        matchIfMissing = true)
public final class DockerCodingBenchmarkExecutor implements CodingBenchmarkExecutionPort {
    private static final String DOCKER = "docker";
    private static final String EGRESS_NETWORK = "bridge";
    private static final long CONTROL_TIMEOUT_MILLIS = 30_000L;
    private static final long RELAY_STARTUP_MILLIS = 2_000L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DockerCommandRunner docker;
    private final CodingBenchmarkRuntimeAttestor attestor;
    private final EvaluationProperties.CodingBenchmark codingBenchmark;
    private final Path oracleScriptPath;
    private final Supplier<String> upstreamApiKeySupplier;
    private final Supplier<String> fallbackApiKeySupplier;

    @Autowired
    public DockerCodingBenchmarkExecutor(EvaluationProperties properties) {
        this(new ProcessDockerCommandRunner(), new CodingBenchmarkRuntimeAttestor(), properties, null, null);
    }

    DockerCodingBenchmarkExecutor(DockerCommandRunner docker) {
        this(docker, new CodingBenchmarkRuntimeAttestor(), new EvaluationProperties(), () -> "test-upstream-key", () -> "");
    }

    DockerCodingBenchmarkExecutor(
            DockerCommandRunner docker,
            CodingBenchmarkRuntimeAttestor attestor,
            EvaluationProperties properties
    ) {
        this(docker, attestor, properties, null, null);
    }

    DockerCodingBenchmarkExecutor(
            DockerCommandRunner docker,
            CodingBenchmarkRuntimeAttestor attestor,
            EvaluationProperties properties,
            Supplier<String> upstreamApiKeySupplier
    ) {
        this(docker, attestor, properties, upstreamApiKeySupplier, null);
    }

    DockerCodingBenchmarkExecutor(
            DockerCommandRunner docker,
            CodingBenchmarkRuntimeAttestor attestor,
            EvaluationProperties properties,
            Supplier<String> upstreamApiKeySupplier,
            Supplier<String> fallbackApiKeySupplier
    ) {
        this.docker = Objects.requireNonNull(docker, "docker command runner must not be null");
        this.attestor = Objects.requireNonNull(attestor, "runtime attestor must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        this.codingBenchmark = properties.getCodingBenchmark();
        this.oracleScriptPath = resolveOracleScriptPath(properties.getRepositoryRoot());
        this.upstreamApiKeySupplier = upstreamApiKeySupplier == null
                ? this::requireUpstreamApiKeyFromEnvironment
                : upstreamApiKeySupplier;
        this.fallbackApiKeySupplier = fallbackApiKeySupplier == null
                ? this::optionalFallbackApiKeyFromEnvironment
                : fallbackApiKeySupplier;
    }

    static Path resolveOracleScriptPath(Path repositoryRoot) {
        Path configured = repositoryRoot == null
                ? Path.of(".")
                : repositoryRoot;
        Path direct = configured.resolve("scripts/evaluation/rd_eval_oracle.py").toAbsolutePath().normalize();
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path current = Path.of(".").toAbsolutePath().normalize();
        for (int index = 0; index < 6 && current != null; index++) {
            Path candidate = current.resolve("scripts/evaluation/rd_eval_oracle.py");
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
            current = current.getParent();
        }
        return direct;
    }

    @Override
    public CodingBenchmarkExecutionResult execute(
            CodingBenchmarkExecutionRequest request,
            CodingBenchmarkExecutionHooks hooks
    ) {
        Objects.requireNonNull(request, "coding benchmark request must not be null");
        CodingBenchmarkExecutionHooks phaseHooks = hooks == null ? CodingBenchmarkExecutionHooks.noop() : hooks;
        CodingBenchmarkRuntimeAttestation attestation = attestor.attest(request, Instant.now().toEpochMilli());
        Path relayTokenFile = request.outputDirectory().resolve(".relay-token.env");
        Path relayProfileFile = request.outputDirectory().resolve("relay-profile.json");
        String relayContainer = CodingBenchmarkTrialNaming.relayContainerName(request.trial());
        String suffix = CodingBenchmarkTrialNaming.trialSuffix(request.trial());
        String network = "rd-eval-network-" + suffix;
        boolean relayStarted = false;
        try {
            Files.createDirectories(request.outputDirectory());
            writeRelayTokenFile(relayTokenFile, request.relayToken());
            requireInputs(request);
            if (Files.exists(request.candidatePatch())) {
                return infrastructureFailure(-1, -1, "candidate patch output already exists before Agent", attestation,
                        request.outputDirectory());
            }
            String upstreamApiKey = upstreamApiKeySupplier.get();
            String fallbackApiKey = fallbackApiKeySupplier.get();
            writeRelayProfile(relayProfileFile);

            DockerCommandResult createNetwork = docker.run(networkCreateCommand(network, request), CONTROL_TIMEOUT_MILLIS);
            if (!createNetwork.succeeded()) {
                return infrastructureFailure(-1, -1, "cannot create isolated trial network", attestation, request.outputDirectory());
            }
            DockerCommandResult cleanup;
            CodingBenchmarkExecutionResult phaseResult;
            try {
                DockerCommandResult relay = docker.run(
                        relayCommand(request, network, relayContainer, relayProfileFile, relayTokenFile,
                                upstreamApiKey, fallbackApiKey),
                        CONTROL_TIMEOUT_MILLIS);
                if (!relay.succeeded()) {
                    phaseResult = new CodingBenchmarkExecutionResult(
                            -1, -1, true, relayFailureMessage("relay container failed to start", relay), attestation);
                } else {
                    relayStarted = true;
                    DockerCommandResult egress = docker.run(
                            relayEgressConnectCommand(relayContainer), CONTROL_TIMEOUT_MILLIS);
                    if (!egress.succeeded()) {
                        phaseResult = new CodingBenchmarkExecutionResult(
                                -1, -1, true,
                                relayFailureMessage("relay container egress connect failed", egress),
                                attestation);
                    } else {
                        waitForRelayStartup();
                        DockerCommandResult agent = docker.run(
                                agentCommand(request, network, suffix, relayTokenFile), request.agentTimeoutMillis());
                        boolean agentFinished = agent.succeeded()
                                || hasStructuredAgentResult(request.agentOutputDirectory());
                        boolean patchReady = ensureCandidatePatch(request);
                        if (!agentFinished && !patchReady) {
                            // Hard agent failure with nothing to score.
                            phaseResult = new CodingBenchmarkExecutionResult(
                                    agent.exitCode(), -1, false, "agent container failed", attestation);
                        } else if (!patchReady) {
                            phaseResult = new CodingBenchmarkExecutionResult(
                                    agent.succeeded() ? agent.exitCode() : 0, -1, false,
                                    "agent did not publish a candidate patch", attestation);
                        } else {
                            // Prefer scoring a published patch even when the agent exited uncleanly
                            // without result.json (e.g. SIGKILL mid-tool after budget warning).
                            // That yields TEST_FAIL/PASS instead of PROTOCOL_ERROR.
                            phaseHooks.beforeOracle();
                            DockerCommandResult oracle = docker.run(oracleCommand(request, suffix), request.oracleTimeoutMillis());
                            phaseResult = oracle.succeeded()
                                    ? new CodingBenchmarkExecutionResult(agent.exitCode(), oracle.exitCode(), false, "", attestation)
                                    : new CodingBenchmarkExecutionResult(agent.exitCode(), oracle.exitCode(), false,
                                    "oracle container reported a test failure", attestation);
                        }
                    }
                }
            } finally {
                if (relayStarted) {
                    docker.run(List.of(DOCKER, "rm", "-f", relayContainer), CONTROL_TIMEOUT_MILLIS);
                }
                cleanup = docker.run(List.of(DOCKER, "network", "rm", network), CONTROL_TIMEOUT_MILLIS);
            }
            if (!cleanup.succeeded()) {
                return infrastructureFailure(
                        phaseResult.agentExitCode(), phaseResult.oracleExitCode(),
                        "network cleanup failed for " + network, attestation, request.outputDirectory());
            }
            return phaseResult;
        } catch (IOException exception) {
            return infrastructureFailure(-1, -1, "docker invocation failed: " + safe(exception.getMessage()), attestation,
                    request.outputDirectory());
        } finally {
            try {
                Files.deleteIfExists(relayTokenFile);
                Files.deleteIfExists(relayProfileFile);
            } catch (IOException exception) {
                writeQuarantineMarker(request.outputDirectory(), "cannot remove one-time relay token file");
            }
        }
    }

    private String requireUpstreamApiKeyFromEnvironment() {
        String envName = codingBenchmark.getModelProvider().getApiKeyEnv();
        String value = System.getenv(envName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("upstream API key environment variable is missing: " + envName);
        }
        return value.strip();
    }

    private String optionalFallbackApiKeyFromEnvironment() {
        EvaluationProperties.CodingBenchmark.ModelProvider provider = codingBenchmark.getModelProvider();
        if (!provider.hasFallback()) {
            return "";
        }
        String envName = provider.getFallback().getApiKeyEnv();
        String value = System.getenv(envName);
        return value == null ? "" : value.strip();
    }

    private void writeRelayProfile(Path relayProfileFile) throws IOException {
        EvaluationProperties.CodingBenchmark.ModelProvider provider = codingBenchmark.getModelProvider();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("model", provider.getModel());
        profile.put("upstream_url", provider.getBaseUrl());
        profile.put("allowed_paths", provider.getAllowedPaths());
        if (provider.hasFallback()) {
            EvaluationProperties.CodingBenchmark.ModelProvider.FallbackProvider fallback = provider.getFallback();
            Map<String, Object> fallbackProfile = new LinkedHashMap<>();
            fallbackProfile.put("name", fallback.getName());
            fallbackProfile.put("model", fallback.getModel());
            fallbackProfile.put("upstream_url", fallback.getBaseUrl());
            profile.put("fallback", fallbackProfile);
        }
        Files.writeString(relayProfileFile, OBJECT_MAPPER.writeValueAsString(profile) + "\n", StandardCharsets.UTF_8);
    }

    private void waitForRelayStartup() {
        try {
            Thread.sleep(RELAY_STARTUP_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private CodingBenchmarkExecutionResult infrastructureFailure(
            int agentExitCode,
            int oracleExitCode,
            String message,
            CodingBenchmarkRuntimeAttestation attestation,
            Path outputDirectory
    ) {
        writeQuarantineMarker(outputDirectory, message);
        return new CodingBenchmarkExecutionResult(agentExitCode, oracleExitCode, true, message, attestation);
    }

    private static void requireInputs(CodingBenchmarkExecutionRequest request) {
        if (!Files.isDirectory(request.agentRepository()) || !Files.isDirectory(request.agentCache())
                || !Files.isDirectory(request.agentOutputDirectory()) || !Files.isDirectory(request.verifierRepository())
                || !Files.isDirectory(request.verifierCache())) {
            throw new IllegalArgumentException("coding benchmark execution inputs are not prepared");
        }
        Path agentInput = agentInputDirectory(request);
        if (!Files.isDirectory(agentInput)) {
            throw new IllegalArgumentException("agent input directory is not prepared");
        }
        boolean protectedBundle = request.protectedTestBundle() != null && Files.isDirectory(request.protectedTestBundle());
        boolean protectedPatch = request.protectedTestPatch() != null && Files.isRegularFile(request.protectedTestPatch());
        if (protectedBundle == protectedPatch) {
            throw new IllegalArgumentException("exactly one protected test bundle or protected test patch must be prepared");
        }
    }

    private static Path agentInputDirectory(CodingBenchmarkExecutionRequest request) {
        return request.agentOutputDirectory().getParent().resolve("input").toAbsolutePath().normalize();
    }

    private static boolean ensureCandidatePatch(CodingBenchmarkExecutionRequest request) throws IOException {
        CodingBenchmarkPatchExtractor.Result extracted = CodingBenchmarkPatchExtractor.ensure(
                request.agentRepository(),
                request.agentOutputDirectory(),
                request.candidatePatch(),
                request.protectedTestTarget()
        );
        return extracted.ready();
    }

    private static boolean hasStructuredAgentResult(Path agentOutput) {
        Path result = agentOutput.resolve("result.json");
        try {
            return Files.isRegularFile(result) && Files.size(result) > 0L;
        } catch (IOException exception) {
            return false;
        }
    }

    private static List<String> networkCreateCommand(String network, CodingBenchmarkExecutionRequest request) {
        return List.of(
                DOCKER, "network", "create", "--internal",
                "--label", "rd.evaluation.trial=" + request.trial().trialId(),
                "--label", "rd.evaluation.kind=coding-benchmark",
                network
        );
    }

    private List<String> relayCommand(
            CodingBenchmarkExecutionRequest request,
            String network,
            String relayContainer,
            Path relayProfileFile,
            Path relayTokenFile,
            String upstreamApiKey,
            String fallbackApiKey
    ) {
        List<String> command = new ArrayList<>(List.of(
                DOCKER, "run", "-d", "--pull=never", "--init",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--pids-limit", "128", "--memory", "512m", "--cpus", "1",
                "--name", relayContainer,
                "--network", network,
                "--label", "rd.evaluation.trial=" + request.trial().trialId(),
                "--label", "rd.evaluation.kind=coding-benchmark-relay"
        ));
        addReadOnlyMount(command, relayProfileFile, "/etc/relay/profile.json");
        command.add("--env");
        command.add("RD_RELAY_TOKEN=" + request.relayToken());
        command.add("--env");
        command.add("RD_RELAY_PROFILE_PATH=/etc/relay/profile.json");
        command.add("--env");
        command.add("RD_RELAY_UPSTREAM_API_KEY=" + upstreamApiKey);
        if (fallbackApiKey != null && !fallbackApiKey.isBlank()) {
            command.add("--env");
            command.add("RD_RELAY_FALLBACK_API_KEY=" + fallbackApiKey);
        }
        command.add(codingBenchmark.resolvedRelayImage());
        return List.copyOf(command);
    }

    private static List<String> relayEgressConnectCommand(String relayContainer) {
        return List.of(DOCKER, "network", "connect", EGRESS_NETWORK, relayContainer);
    }

    private static List<String> agentCommand(
            CodingBenchmarkExecutionRequest request,
            String network,
            String suffix,
            Path relayTokenFile
    ) {
        List<String> command = secureRunPrefix("rd-eval-agent-" + suffix, request.agentImage(), network);
        addWritableMount(command, request.agentRepository(), "/work/repo");
        addWritableMount(command, request.agentCache(), "/work/cache");
        addWritableMount(command, request.agentOutputDirectory(), "/work/output");
        addReadOnlyMount(command, agentInputDirectory(request), "/work/input");
        command.add("--workdir");
        command.add("/work/repo");
        command.add("--env-file");
        command.add(relayTokenFile.toAbsolutePath().normalize().toString());
        command.add("--env");
        command.add("PIP_NO_INDEX=1");
        command.add("--env");
        command.add("npm_config_offline=true");
        addPreparedCacheEnvironment(command);
        command.add("--env");
        command.add("RD_EVAL_CANDIDATE_PATCH=/work/output/candidate.patch");
        command.add("--env");
        command.add("RD_PI_MAX_RAW_EVENT_BYTES=2097152");
        command.add(request.agentImage());
        command.addAll(request.agentCommand());
        return List.copyOf(command);
    }

    private List<String> oracleCommand(CodingBenchmarkExecutionRequest request, String suffix) {
        List<String> command = secureRunPrefix("rd-eval-oracle-" + suffix, request.oracleImage(), "none");
        command.add("--entrypoint");
        command.add("");
        addWritableMount(command, request.verifierRepository(), "/work/verifier");
        addWritableMount(command, request.verifierCache(), "/work/cache");
        addReadOnlyMount(command, request.candidatePatch(), "/input/candidate.patch");
        if (request.protectedTestBundle() != null) {
            addReadOnlyMount(command, request.protectedTestBundle(), "/input/protected-tests");
        } else {
            addReadOnlyMount(command, request.protectedTestPatch(), "/input/runtime-withheld.patch");
        }
        addWritableMount(command, request.outputDirectory(), "/work/output");
        // Toolchain images (java21/java17/node) lack the thin-oracle script layer; always mount it.
        if (Files.isRegularFile(oracleScriptPath)) {
            addReadOnlyMount(command, oracleScriptPath, "/opt/rd-pi-bridge/rd_eval_oracle.py");
        }
        command.add("--workdir");
        command.add("/work/verifier");
        command.add("--env");
        command.add("RD_EVAL_ORACLE_NETWORK=none");
        addPreparedCacheEnvironment(command);
        command.add(request.oracleImage());
        command.addAll(request.oracleCommand());
        return List.copyOf(command);
    }

    private static List<String> secureRunPrefix(String name, String image, String network) {
        return new ArrayList<>(List.of(
                DOCKER, "run", "--rm", "--pull=never", "--init", "--read-only",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--pids-limit", "512", "--memory", "8g", "--cpus", "4",
                "--user", "10001:10001", "--name", name, "--network", network
        ));
    }

    private static void addWritableMount(List<String> command, Path source, String target) {
        command.add("--mount");
        command.add("type=bind,src=" + mountPath(source) + ",dst=" + target);
    }

    private static void addReadOnlyMount(List<String> command, Path source, String target) {
        command.add("--mount");
        command.add("type=bind,src=" + mountPath(source) + ",dst=" + target + ",readonly");
    }

    private static void addPreparedCacheEnvironment(List<String> command) {
        for (String value : List.of(
                "PIP_CACHE_DIR=/work/cache/pip",
                "GRADLE_USER_HOME=/work/cache/gradle",
                "MAVEN_OPTS=-Dmaven.repo.local=/work/cache/m2 -Dfile.encoding=UTF-8",
                "npm_config_cache=/work/cache/npm",
                "YARN_CACHE_FOLDER=/work/cache/yarn",
                "LANG=C.UTF-8",
                "LC_ALL=C.UTF-8"
        )) {
            command.add("--env");
            command.add(value);
        }
    }

    private static String mountPath(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        if (value.contains(",")) {
            throw new IllegalArgumentException("Docker mount source must not contain a comma");
        }
        return value;
    }

    private static void writeQuarantineMarker(Path outputDirectory, String message) {
        try {
            Files.createDirectories(outputDirectory);
            Files.writeString(outputDirectory.resolve("INFRA_QUARANTINE"), safe(message) + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The returned infrastructure error remains authoritative when a host disk problem prevents the marker.
        }
    }

    private static void writeRelayTokenFile(Path path, String relayToken) throws IOException {
        Files.writeString(path, "RD_EVAL_MODEL_RELAY_TOKEN=" + relayToken + "\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // The Windows filesystem does not expose POSIX modes; the file still remains task-private and short-lived.
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static String relayFailureMessage(String prefix, DockerCommandResult result) {
        String diagnostic = dockerDiagnostic(result.stderr());
        return diagnostic.isEmpty() ? prefix : prefix + ": " + diagnostic;
    }

    private static String dockerDiagnostic(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "";
        }
        String snippet = stderr.strip().replaceAll("\\s+", " ");
        int limit = 200;
        if (snippet.length() > limit) {
            snippet = snippet.substring(0, limit) + "...";
        }
        return snippet;
    }

    @FunctionalInterface
    public interface DockerCommandRunner {
        DockerCommandResult run(List<String> command, long timeoutMillis) throws IOException;
    }

    public record DockerCommandResult(int exitCode, String stdout, String stderr) {
        public DockerCommandResult {
            stdout = safe(stdout);
            stderr = safe(stderr);
        }

        public boolean succeeded() {
            return exitCode == 0;
        }
    }

    private static final class ProcessDockerCommandRunner implements DockerCommandRunner {
        @Override
        public DockerCommandResult run(List<String> command, long timeoutMillis) throws IOException {
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.PIPE);
            Process process = processBuilder.start();
            try {
                if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    return new DockerCommandResult(124, "", "docker command timed out");
                }
                String stderr = readStream(process.getErrorStream());
                return new DockerCommandResult(process.exitValue(), "", stderr);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("docker command interrupted", exception);
            }
        }

        private static String readStream(InputStream stream) throws IOException {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
