package com.wish.rd.bootstrap.oracle.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.oracle.HostOwnedAssertionGate;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.docker.model.RepairWorkspace;
import com.wish.rd.exec.repair.execution.model.RepairInputAttachment;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.oracle.HostVerifierWorkspaceFactory;
import com.wish.rd.exec.repair.oracle.model.HostVerifierWorkspace;
import com.wish.rd.exec.repair.oracle.model.HostVerifierWorkspaceRequest;
import com.wish.rd.rag.qa.model.QaValidationProfileCommand;

import java.io.IOException;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replays only a manifest-verified Coding patch into a fresh Host verifier workspace.
 * It is invoked by {@link HostOwnedAssertionGate}; no field from an agent result selects
 * the workspace, patch, or HTTP base URL.
 */
public final class CleanHostVerifierWorkspaceFactory implements HostVerifierWorkspaceFactory {

    private static final String CANDIDATE_PATCH_NAME = "candidate-patch.diff";
    private static final String CANDIDATE_PATCH_MIME_TYPE = "text/x-diff";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient READINESS_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final RepairWorkspaceFactory workspaceFactory;
    private final RepairWorkspaceRepositoryPort workspaceRepository;
    private final RuntimeConfiguration runtimeConfiguration;

    /**
     * Creates a clean Host replay factory.
     *
     * @param workspaceFactory Host-configured local workspace factory
     * @param workspaceRepository Host repository preparation port
     */
    public CleanHostVerifierWorkspaceFactory(
            RepairWorkspaceFactory workspaceFactory,
            RepairWorkspaceRepositoryPort workspaceRepository
    ) {
        this(workspaceFactory, workspaceRepository, RuntimeConfiguration.defaults());
    }

    /**
     * Creates a clean Host replay factory with bounded process startup configuration.
     *
     * @param workspaceFactory Host-configured local workspace factory
     * @param workspaceRepository Host repository preparation port
     * @param runtimeConfiguration Host-controlled readiness and cleanup limits
     */
    public CleanHostVerifierWorkspaceFactory(
            RepairWorkspaceFactory workspaceFactory,
            RepairWorkspaceRepositoryPort workspaceRepository,
            RuntimeConfiguration runtimeConfiguration
    ) {
        this.workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory must not be null");
        this.workspaceRepository = Objects.requireNonNull(
                workspaceRepository,
                "workspaceRepository must not be null"
        );
        this.runtimeConfiguration = runtimeConfiguration == null
                ? RuntimeConfiguration.defaults()
                : runtimeConfiguration;
    }

    /**
     * Creates a unique verifier attempt, applies the manifest-verified patch, and returns the clean checkout.
     *
     * @param command Host-created QA command
     * @param request immutable Host scope identity
     * @return checkout and profile-derived HTTP context for assertion execution
     * @throws Exception when the Host cannot establish an isolated replay
     */
    @Override
    public HostVerifierWorkspace create(RepairJobCommand command, HostVerifierWorkspaceRequest request) throws Exception {
        RepairJobCommand source = Objects.requireNonNull(command, "command must not be null");
        HostVerifierWorkspaceRequest scope = Objects.requireNonNull(request, "request must not be null");
        requireQaCommand(source);
        RepairInputAttachment patch = verifiedCandidatePatch(source);
        RepairJobCommand replayCommand = replayCommand(source, patch);
        RepairWorkspace workspace = workspaceFactory.createProviderAttempt(replayCommand, verifierAttemptId(scope));
        workspaceRepository.prepare(replayCommand, workspace);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("hostAssertionScope", scope.scope());
        attributes.put("hostVerifierWorkspace", "clean-replay");
        if (!scope.runtimeRequired()) {
            return new HostVerifierWorkspace(workspace.repoDirectory(), "", attributes);
        }

        ReplayProfile profile = replayProfile(source);
        ProcessRuntime runtime = startRuntime(workspace.repoDirectory(), profile);
        attributes.put("hostQaProfileSource", profile.source());
        attributes.put("hostVerifierRuntime", "clean-replay-process");
        attributes.put("hostVerifierRuntimePid", Long.toString(runtime.pid()));
        return new HostVerifierWorkspace(
                workspace.repoDirectory(),
                runtime.baseUrl(),
                attributes,
                runtime
        );
    }

    private static void requireQaCommand(RepairJobCommand command) {
        String role = command.contextJson().getOrDefault("agentRole", "").strip();
        if (!"QA_AGENT".equals(role)) {
            throw new IllegalStateException("Host verifier workspace requires a Host-created QA command");
        }
    }

    private static RepairInputAttachment verifiedCandidatePatch(RepairJobCommand command) {
        List<RepairInputAttachment> candidates = command.attachments().stream()
                .filter(attachment -> CANDIDATE_PATCH_NAME.equals(attachment.filename()))
                .toList();
        if (candidates.size() != 1) {
            throw new IllegalStateException("Host verifier requires exactly one candidate-patch.diff attachment");
        }
        RepairInputAttachment patch = candidates.getFirst();
        if (!CANDIDATE_PATCH_MIME_TYPE.equalsIgnoreCase(patch.mimeType())) {
            throw new IllegalStateException("Host verifier candidate patch must use text/x-diff MIME type");
        }
        HandoffPatchManifest manifest = candidatePatchManifest(command.contextJson()
                .getOrDefault("upstreamHandoffManifestJson", ""));
        String actualDigest = sha256(patch.content());
        if (!actualDigest.equals(manifest.sha256())) {
            throw new IllegalStateException("Host verifier candidate patch digest does not match upstream handoff manifest");
        }
        if (patch.content().length != manifest.bytes()) {
            throw new IllegalStateException("Host verifier candidate patch size does not match upstream handoff manifest");
        }
        return patch;
    }

    private static HandoffPatchManifest candidatePatchManifest(String rawManifest) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawManifest == null ? "" : rawManifest);
            if (root == null || !root.isArray()) {
                throw new IllegalStateException("upstream handoff manifest must be an array");
            }
            List<HandoffPatchManifest> matches = new ArrayList<>();
            for (JsonNode entry : root) {
                if (!entry.isObject()) {
                    continue;
                }
                if (!"CODING_AGENT".equalsIgnoreCase(text(entry, "sourceRole"))
                        || !"QA_AGENT".equalsIgnoreCase(text(entry, "targetRole"))
                        || !"/work/input/attachments/candidate-patch.diff".equals(text(entry, "path"))
                        || !entry.path("applied").asBoolean(false)) {
                    continue;
                }
                String digest = normalizeSha256(text(entry, "sha256"));
                long bytes = entry.path("bytes").asLong(-1L);
                if (bytes <= 0L) {
                    throw new IllegalStateException("candidate patch handoff manifest bytes must be positive");
                }
                matches.add(new HandoffPatchManifest(digest, bytes));
            }
            if (matches.size() != 1) {
                throw new IllegalStateException("upstream handoff manifest must contain exactly one verified candidate patch");
            }
            return matches.getFirst();
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("upstream handoff manifest is invalid", exception);
        }
    }

    private static ReplayProfile replayProfile(RepairJobCommand command) {
        String taskOverride = command.contextJson().getOrDefault("qaTaskOverrideJson", "");
        if (!taskOverride.isBlank()) {
            return parseReplayProfile(taskOverride, "TASK_OVERRIDE");
        }
        String projectProfile = command.contextJson().getOrDefault("qaProjectProfileJson", "");
        if (!projectProfile.isBlank()) {
            return parseReplayProfile(projectProfile, "PROJECT_PROFILE");
        }
        throw new IllegalStateException(
                "HTTP/browser Host assertions require a Host QA profile with baseUrl and startCommand"
        );
    }

    private static ReplayProfile parseReplayProfile(String profileJson, String source) {
        try {
            JsonNode profile = OBJECT_MAPPER.readTree(profileJson);
            if (profile == null || !profile.isObject()) {
                throw new IllegalStateException("Host QA profile context must be an object");
            }
            QaValidationProfileCommand validated = new QaValidationProfileCommand(
                    text(profile, "mode"),
                    text(profile, "baseUrl"),
                    text(profile, "startCommand"),
                    text(profile, "healthPath"),
                    strings(profile.path("allowedHosts")),
                    List.of()
            );
            if (!"REQUIRED".equals(validated.mode())) {
                throw new IllegalStateException("Host verifier runtime requires a REQUIRED QA profile");
            }
            if (validated.startCommand().isBlank()) {
                throw new IllegalStateException("Host verifier runtime requires a non-blank QA startCommand");
            }
            return new ReplayProfile(
                    localConfiguredBaseUrl(validated.baseUrl()),
                    validated.startCommand(),
                    validated.healthPath().isBlank() ? "/" : validated.healthPath(),
                    source
            );
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Host QA profile context is invalid", exception);
        }
    }

    private ProcessRuntime startRuntime(Path workspaceRoot, ReplayProfile profile) throws Exception {
        int runtimePort = reserveLoopbackPort();
        URI runtimeBaseUrl = runtimeBaseUrl(profile.configuredBaseUrl(), runtimePort);
        Path logDirectory = workspaceRoot.resolve(".rd-bot-host-verifier").toAbsolutePath().normalize();
        if (!logDirectory.startsWith(workspaceRoot.toAbsolutePath().normalize())) {
            throw new IllegalStateException("Host verifier runtime log path escapes clean workspace");
        }
        Files.createDirectories(logDirectory);
        Path logFile = logDirectory.resolve("runtime-" + runtimePort + ".log");
        ProcessBuilder processBuilder = new ProcessBuilder(
                "/bin/sh",
                "-lc",
                commandForRuntimePort(profile.startCommand(), profile.configuredBaseUrl().getPort(), runtimePort)
        );
        processBuilder.directory(workspaceRoot.toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(logFile.toFile());
        Map<String, String> environment = processBuilder.environment();
        environment.put("PORT", Integer.toString(runtimePort));
        environment.put("RD_QA_PORT", Integer.toString(runtimePort));
        environment.put("HOST", "127.0.0.1");
        environment.put("HOSTNAME", "127.0.0.1");
        environment.put("CI", "true");

        ProcessRuntime runtime = new ProcessRuntime(processBuilder.start(), runtimeBaseUrl.toString(), runtimeConfiguration);
        try {
            awaitReadiness(runtime, runtimeBaseUrl.resolve(profile.healthPath()));
            return runtime;
        } catch (Exception exception) {
            try {
                runtime.close();
            } catch (Exception cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
    }

    private void awaitReadiness(ProcessRuntime runtime, URI readinessUrl) throws Exception {
        Instant deadline = Instant.now().plusMillis(runtimeConfiguration.startupTimeoutMillis());
        while (Instant.now().isBefore(deadline)) {
            if (!runtime.isAlive()) {
                throw new IllegalStateException("Host verifier startCommand exited before readiness with code "
                        + runtime.exitCode());
            }
            if (isReady(readinessUrl, runtimeConfiguration.readinessRequestTimeoutMillis())) {
                return;
            }
            try {
                Thread.sleep(runtimeConfiguration.readinessPollMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Host verifier readiness wait was interrupted", exception);
            }
        }
        throw new IllegalStateException("Host verifier startCommand timed out waiting for " + readinessUrl
                + " after " + runtimeConfiguration.startupTimeoutMillis() + "ms");
    }

    private static boolean isReady(URI readinessUrl, long timeoutMillis) throws InterruptedException {
        try {
            HttpRequest request = HttpRequest.newBuilder(readinessUrl)
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .GET()
                    .build();
            int status = READINESS_CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status >= 200 && status < 400;
        } catch (IOException exception) {
            return false;
        }
    }

    private static int reserveLoopbackPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Host verifier cannot reserve a loopback port", exception);
        }
    }

    private static URI localConfiguredBaseUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !isLoopbackHost(uri.getHost())) {
                throw new IllegalStateException(
                        "Host verifier runtime baseUrl must be a local credential-free HTTP URL"
                );
            }
            return uri;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Host verifier runtime baseUrl is invalid", exception);
        }
    }

    private static URI runtimeBaseUrl(URI configuredBaseUrl, int runtimePort) {
        try {
            String path = configuredBaseUrl.getRawPath() == null ? "" : configuredBaseUrl.getRawPath();
            return new URI("http", null, "127.0.0.1", runtimePort, path, null, null);
        } catch (Exception exception) {
            throw new IllegalStateException("Host verifier runtime URL cannot be derived", exception);
        }
    }

    private static boolean isLoopbackHost(String host) {
        String normalized = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || "::1".equals(normalized)
                || "[::1]".equals(normalized)
                || normalized.matches("127(?:\\.[0-9]{1,3}){3}");
    }

    private static String commandForRuntimePort(String startCommand, int configuredPort, int runtimePort) {
        String command = startCommand == null ? "" : startCommand.strip();
        if (command.isBlank()) {
            throw new IllegalStateException("Host verifier runtime startCommand must not be blank");
        }
        if (configuredPort <= 0) {
            return command;
        }
        Pattern configuredPortPattern = Pattern.compile(
                "(?<![0-9])" + Pattern.quote(Integer.toString(configuredPort)) + "(?![0-9])"
        );
        return configuredPortPattern.matcher(command)
                .replaceAll(Matcher.quoteReplacement(Integer.toString(runtimePort)));
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (value.isTextual() && !value.asText("").isBlank()) {
                values.add(value.asText("").strip());
            }
        }
        return List.copyOf(values);
    }

    private static RepairJobCommand replayCommand(RepairJobCommand source, RepairInputAttachment patch) {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("workflowTaskId", source.contextJson().getOrDefault("workflowTaskId", ""));
        context.put("stageRunId", source.contextJson().getOrDefault("stageRunId", ""));
        context.put("agentRole", "QA_AGENT");
        context.put("hostVerifierReplay", "true");

        Map<String, String> policy = new LinkedHashMap<>();
        policy.put("repositoryDeliveryMode", "LOCAL_ONLY");
        policy.put("applyCandidatePatch", "true");
        return new RepairJobCommand(
                source.repairRecordId(),
                source.taskId(),
                source.ticketId(),
                source.ticketTitle(),
                "Host-owned assertion replay",
                source.repositoryUrl(),
                source.repoOwner(),
                source.repoName(),
                source.baseBranch(),
                source.workBranch(),
                context,
                policy,
                List.of(patch)
        );
    }

    private static String verifierAttemptId(HostVerifierWorkspaceRequest request) {
        return "host-oracle-" + request.scope().toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID();
    }

    private static String normalizeSha256(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalStateException("candidate patch handoff manifest sha256 must be sha256: hex digest");
        }
        return normalized;
    }

    private static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.isTextual() ? value.asText("").strip() : "";
    }

    private record HandoffPatchManifest(String sha256, long bytes) {
    }

    private record ReplayProfile(
            URI configuredBaseUrl,
            String startCommand,
            String healthPath,
            String source
    ) {
    }

    private static final class ProcessRuntime implements AutoCloseable {

        private final Process process;
        private final String baseUrl;
        private final RuntimeConfiguration configuration;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ProcessRuntime(Process process, String baseUrl, RuntimeConfiguration configuration) {
            this.process = Objects.requireNonNull(process, "process must not be null");
            this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
            this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        }

        private long pid() {
            return process.pid();
        }

        private String baseUrl() {
            return baseUrl;
        }

        private boolean isAlive() {
            return process.isAlive();
        }

        private int exitCode() {
            return process.isAlive() ? -1 : process.exitValue();
        }

        @Override
        public void close() throws Exception {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            List<ProcessHandle> descendants = process.toHandle().descendants().toList();
            descendants.forEach(ProcessHandle::destroy);
            process.destroy();
            try {
                if (!process.waitFor(configuration.shutdownTimeoutMillis(), TimeUnit.MILLISECONDS)) {
                    descendants.forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    process.waitFor(configuration.shutdownTimeoutMillis(), TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException exception) {
                descendants.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Host verifier runtime cleanup was interrupted", exception);
            }
        }
    }

    /**
     * Bounded timing controls for a Host-started clean replay runtime.
     * The production default is 300 seconds so Next.js production builds have time to complete.
     *
     * @param startupTimeoutMillis total process-start and readiness budget
     * @param readinessPollMillis interval between readiness probes
     * @param shutdownTimeoutMillis graceful/forced process cleanup budget
     */
    public record RuntimeConfiguration(
            long startupTimeoutMillis,
            long readinessPollMillis,
            long shutdownTimeoutMillis
    ) {

        /** Validates bounded Host process timing controls. */
        public RuntimeConfiguration {
            if (startupTimeoutMillis <= 0L || startupTimeoutMillis > 300_000L) {
                throw new IllegalArgumentException("Host verifier startupTimeoutMillis must be between 1 and 300000");
            }
            if (readinessPollMillis <= 0L || readinessPollMillis > startupTimeoutMillis) {
                throw new IllegalArgumentException("Host verifier readinessPollMillis must be positive and bounded");
            }
            if (shutdownTimeoutMillis <= 0L || shutdownTimeoutMillis > 30_000L) {
                throw new IllegalArgumentException("Host verifier shutdownTimeoutMillis must be between 1 and 30000");
            }
        }

        /** Returns the production-safe runtime defaults. */
        public static RuntimeConfiguration defaults() {
            return new RuntimeConfiguration(300_000L, 200L, 5_000L);
        }

        private long readinessRequestTimeoutMillis() {
            return Math.max(1L, Math.min(2_000L, readinessPollMillis * 4L));
        }
    }
}
