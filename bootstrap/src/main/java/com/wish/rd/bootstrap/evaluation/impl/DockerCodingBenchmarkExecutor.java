package com.wish.rd.bootstrap.evaluation.impl;

import com.wish.rd.engine.evaluation.CodingBenchmarkExecutionPort;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionRequest;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionResult;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkRuntimeAttestation;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Builds isolated Docker argv for one prepared coding trial without reusing the general repair
 * executor's broader workspace contract.
 */
public final class DockerCodingBenchmarkExecutor implements CodingBenchmarkExecutionPort {
    private static final String DOCKER = "docker";
    private static final long CONTROL_TIMEOUT_MILLIS = 30_000L;

    private final DockerCommandRunner docker;
    private final CodingBenchmarkRuntimeAttestor attestor;

    /** Creates a production executor that invokes Docker through argv-only {@link ProcessBuilder}. */
    public DockerCodingBenchmarkExecutor() {
        this(new ProcessDockerCommandRunner(), new CodingBenchmarkRuntimeAttestor());
    }

    /** Creates an executor with a narrow injectable Docker process port for deterministic testing. */
    public DockerCodingBenchmarkExecutor(DockerCommandRunner docker) {
        this(docker, new CodingBenchmarkRuntimeAttestor());
    }

    DockerCodingBenchmarkExecutor(DockerCommandRunner docker, CodingBenchmarkRuntimeAttestor attestor) {
        this.docker = Objects.requireNonNull(docker, "docker command runner must not be null");
        this.attestor = Objects.requireNonNull(attestor, "runtime attestor must not be null");
    }

    /** Runs the Agent in a private internal network, then the Oracle with no network and no Agent mounts. */
    @Override
    public CodingBenchmarkExecutionResult execute(CodingBenchmarkExecutionRequest request) {
        Objects.requireNonNull(request, "coding benchmark request must not be null");
        CodingBenchmarkRuntimeAttestation attestation = attestor.attest(request, Instant.now().toEpochMilli());
        Path relayTokenFile = request.outputDirectory().resolve(".relay-token.env");
        try {
            Files.createDirectories(request.outputDirectory());
            writeRelayTokenFile(relayTokenFile, request.relayToken());
            requireInputs(request);
            String suffix = trialSuffix(request.trial());
            String network = "rd-eval-network-" + suffix;
            DockerCommandResult createNetwork = docker.run(networkCreateCommand(network, request), CONTROL_TIMEOUT_MILLIS);
            if (!createNetwork.succeeded()) {
                return infrastructureFailure(-1, -1, "cannot create isolated trial network", attestation, request.outputDirectory());
            }
            DockerCommandResult cleanup;
            CodingBenchmarkExecutionResult phaseResult;
            try {
                DockerCommandResult agent = docker.run(
                        agentCommand(request, network, suffix, relayTokenFile), request.agentTimeoutMillis());
                if (!agent.succeeded()) {
                    phaseResult = new CodingBenchmarkExecutionResult(
                            agent.exitCode(), -1, false, "agent container failed", attestation);
                } else {
                    DockerCommandResult oracle = docker.run(oracleCommand(request, suffix), request.oracleTimeoutMillis());
                    phaseResult = oracle.succeeded()
                            ? new CodingBenchmarkExecutionResult(agent.exitCode(), oracle.exitCode(), false, "", attestation)
                            : new CodingBenchmarkExecutionResult(agent.exitCode(), oracle.exitCode(), false,
                            "oracle container reported a test failure", attestation);
                }
            } finally {
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
            } catch (IOException exception) {
                writeQuarantineMarker(request.outputDirectory(), "cannot remove one-time relay token file");
            }
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
                || !Files.isDirectory(request.verifierRepository()) || !Files.isDirectory(request.protectedTestBundle())
                || !Files.isRegularFile(request.candidatePatch())) {
            throw new IllegalArgumentException("coding benchmark execution inputs are not prepared");
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

    private static List<String> agentCommand(
            CodingBenchmarkExecutionRequest request,
            String network,
            String suffix,
            Path relayTokenFile
    ) {
        List<String> command = secureRunPrefix("rd-eval-agent-" + suffix, request.agentImage(), network);
        addWritableMount(command, request.agentRepository(), "/work/repo");
        addWritableMount(command, request.agentCache(), "/work/cache");
        command.add("--workdir");
        command.add("/work/repo");
        command.add("--env-file");
        command.add(relayTokenFile.toAbsolutePath().normalize().toString());
        command.add("--env");
        command.add("PIP_NO_INDEX=1");
        command.add("--env");
        command.add("npm_config_offline=true");
        command.add(request.agentImage());
        command.addAll(request.agentCommand());
        return List.copyOf(command);
    }

    private static List<String> oracleCommand(CodingBenchmarkExecutionRequest request, String suffix) {
        List<String> command = secureRunPrefix("rd-eval-oracle-" + suffix, request.oracleImage(), "none");
        // The shared lower layer intentionally carries the Agent bridge. Oracle commands are frozen argv values,
        // so clear that image entrypoint and invoke the verifier executable directly.
        command.add("--entrypoint");
        command.add("");
        addWritableMount(command, request.verifierRepository(), "/work/verifier");
        addReadOnlyMount(command, request.candidatePatch(), "/input/candidate.patch");
        addReadOnlyMount(command, request.protectedTestBundle(), "/input/protected-tests");
        addWritableMount(command, request.outputDirectory(), "/work/output");
        command.add("--workdir");
        command.add("/work/verifier");
        command.add("--env");
        command.add("RD_EVAL_ORACLE_NETWORK=none");
        command.add(request.oracleImage());
        command.addAll(request.oracleCommand());
        return List.copyOf(command);
    }

    private static List<String> secureRunPrefix(String name, String image, String network) {
        List<String> command = new ArrayList<>(List.of(
                DOCKER, "run", "--rm", "--pull=never", "--init", "--read-only",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--pids-limit", "512", "--memory", "8g", "--cpus", "4",
                "--user", "10001:10001", "--name", name, "--network", network
        ));
        return command;
    }

    private static void addWritableMount(List<String> command, Path source, String target) {
        command.add("--mount");
        command.add("type=bind,src=" + mountPath(source) + ",dst=" + target);
    }

    private static void addReadOnlyMount(List<String> command, Path source, String target) {
        command.add("--mount");
        command.add("type=bind,src=" + mountPath(source) + ",dst=" + target + ",readonly");
    }

    private static String mountPath(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        if (value.contains(",")) {
            throw new IllegalArgumentException("Docker mount source must not contain a comma");
        }
        return value;
    }

    private static String trialSuffix(CodingBenchmarkTrial trial) {
        return trial.caseId() + "-" + trial.arm().name().toLowerCase(java.util.Locale.ROOT);
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

    /** Narrow Docker process boundary used by the executor and a capturing test double. */
    @FunctionalInterface
    public interface DockerCommandRunner {
        DockerCommandResult run(List<String> command, long timeoutMillis) throws IOException;
    }

    /** Redacted process result; command argv remains test-local and is never persisted as an attestation field. */
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
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try {
                if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    return new DockerCommandResult(124, "", "docker command timed out");
                }
                return new DockerCommandResult(
                        process.exitValue(),
                        "",
                        ""
                );
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("docker command interrupted", exception);
            }
        }
    }
}
