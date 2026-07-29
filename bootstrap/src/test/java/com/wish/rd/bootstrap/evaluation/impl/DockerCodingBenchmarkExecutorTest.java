package com.wish.rd.bootstrap.evaluation.impl;

import com.wish.rd.engine.evaluation.model.CodingBenchmarkArm;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionRequest;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionResult;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkRuntimeAttestation;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerCodingBenchmarkExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSendOnlyPatchMetadataToVerifier() throws Exception {
        CapturingDockerRunner runner = new CapturingDockerRunner();
        DockerCodingBenchmarkExecutor executor = new DockerCodingBenchmarkExecutor(runner);
        CodingBenchmarkExecutionRequest request = request();

        executor.execute(request);

        List<String> oracleCommand = runner.commands().stream()
                .filter(command -> command.contains("rd-eval-oracle-case-01-a"))
                .findFirst()
                .orElseThrow();
        assertFalse(oracleCommand.toString().contains(request.agentRepository().toString()));
        assertFalse(oracleCommand.toString().contains(request.agentCache().toString()));
        assertTrue(oracleCommand.toString().contains(request.candidatePatch().toString()));
        assertTrue(oracleCommand.contains("none"));
    }

    @Test
    void shouldRecordNetworkAndImageAttestation() throws Exception {
        CapturingDockerRunner runner = new CapturingDockerRunner();
        DockerCodingBenchmarkExecutor executor = new DockerCodingBenchmarkExecutor(runner);

        CodingBenchmarkExecutionResult result = executor.execute(request());
        CodingBenchmarkRuntimeAttestation attestation = result.attestation();

        assertEquals("none", attestation.oracleNetworkMode());
        assertTrue(attestation.agentImageDigest().startsWith("sha256:"));
        assertTrue(attestation.agentNetworkInternal());
    }

    @Test
    void shouldQuarantineTheTrialWhenLabelScopedNetworkCleanupFails() throws Exception {
        CapturingDockerRunner runner = new CapturingDockerRunner(true);
        DockerCodingBenchmarkExecutor executor = new DockerCodingBenchmarkExecutor(runner);
        CodingBenchmarkExecutionRequest request = request();

        CodingBenchmarkExecutionResult result = executor.execute(request);

        assertTrue(result.infrastructureFailure());
        assertTrue(Files.isRegularFile(request.outputDirectory().resolve("INFRA_QUARANTINE")));
    }

    @Test
    void shouldNotExposeOneTimeRelayTokenInTheDockerArgv() throws Exception {
        CapturingDockerRunner runner = new CapturingDockerRunner();
        DockerCodingBenchmarkExecutor executor = new DockerCodingBenchmarkExecutor(runner);
        CodingBenchmarkExecutionRequest request = request();

        executor.execute(request);

        List<String> agentCommand = runner.commands().stream()
                .filter(command -> command.contains("rd-eval-agent-case-01-a"))
                .findFirst()
                .orElseThrow();
        assertFalse(agentCommand.toString().contains(request.relayToken()));
        assertTrue(agentCommand.contains("--env-file"));
    }

    private CodingBenchmarkExecutionRequest request() throws Exception {
        Path agentRepository = Files.createDirectories(tempDir.resolve("agent/repo"));
        Path agentCache = Files.createDirectories(tempDir.resolve("agent/cache"));
        Path verifierRepository = Files.createDirectories(tempDir.resolve("verifier/repo"));
        Path protectedBundle = Files.createDirectories(tempDir.resolve("protected-tests"));
        Path output = Files.createDirectories(tempDir.resolve("output"));
        Path patch = tempDir.resolve("candidate.patch");
        Files.writeString(patch, "diff --git a/a b/a\n");
        Files.writeString(protectedBundle.resolve("test_issue.py"), "def test_regression(): pass\n");
        CodingBenchmarkTrial trial = CodingBenchmarkTrial.queued(
                "trial-1", "100", "case-01", CodingBenchmarkArm.A, 0, 100L
        );
        return new CodingBenchmarkExecutionRequest(
                trial,
                "registry.example/agent@sha256:" + "a".repeat(64),
                "registry.example/oracle@sha256:" + "b".repeat(64),
                agentRepository,
                agentCache,
                verifierRepository,
                patch,
                protectedBundle,
                "tests/runtime_withheld",
                output,
                List.of("/opt/rd/agent-entrypoint"),
                List.of("/opt/rd/oracle-entrypoint"),
                "one-time-relay-token",
                45 * 60_000L,
                8 * 60_000L
        );
    }

    private static final class CapturingDockerRunner implements DockerCodingBenchmarkExecutor.DockerCommandRunner {
        private final List<List<String>> commands = new ArrayList<>();
        private final boolean failCleanup;

        private CapturingDockerRunner() {
            this(false);
        }

        private CapturingDockerRunner(boolean failCleanup) {
            this.failCleanup = failCleanup;
        }

        @Override
        public DockerCodingBenchmarkExecutor.DockerCommandResult run(List<String> command, long timeoutMillis) {
            commands.add(List.copyOf(command));
            if (failCleanup && command.size() >= 3 && command.get(1).equals("network") && command.get(2).equals("rm")) {
                return new DockerCodingBenchmarkExecutor.DockerCommandResult(1, "", "cleanup failed");
            }
            return new DockerCodingBenchmarkExecutor.DockerCommandResult(0, "", "");
        }

        List<List<String>> commands() {
            return List.copyOf(commands);
        }
    }
}
