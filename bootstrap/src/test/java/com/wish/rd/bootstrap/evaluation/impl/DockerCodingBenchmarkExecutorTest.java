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
        int entrypoint = oracleCommand.indexOf("--entrypoint");
        assertTrue(entrypoint >= 0);
        assertEquals("", oracleCommand.get(entrypoint + 1));
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

    @Test
    void shouldMountRuntimeWithheldPatchInsteadOfRequiringABundleDirectory() throws Exception {
        CapturingDockerRunner runner = new CapturingDockerRunner();
        DockerCodingBenchmarkExecutor executor = new DockerCodingBenchmarkExecutor(runner);
        CodingBenchmarkExecutionRequest request = runtimePatchRequest();

        executor.execute(request);

        List<String> oracleCommand = runner.commands().stream()
                .filter(command -> command.contains("rd-eval-oracle-case-01-a"))
                .findFirst()
                .orElseThrow();
        assertTrue(oracleCommand.toString().contains(request.protectedTestPatch().toString()));
        assertTrue(oracleCommand.toString().contains("dst=/input/runtime-withheld.patch,readonly"));
        assertFalse(oracleCommand.toString().contains("dst=/input/protected-tests"));
    }

    @Test
    void shouldRoutePackageManagerStateToTheTrialLocalPreparedCache() throws Exception {
        CapturingDockerRunner runner = new CapturingDockerRunner();
        DockerCodingBenchmarkExecutor executor = new DockerCodingBenchmarkExecutor(runner);

        executor.execute(request());

        List<String> agentCommand = runner.commands().stream()
                .filter(command -> command.contains("rd-eval-agent-case-01-a"))
                .findFirst()
                .orElseThrow();
        assertTrue(agentCommand.contains("GRADLE_USER_HOME=/work/cache/gradle"));
        assertTrue(agentCommand.contains("MAVEN_OPTS=-Dmaven.repo.local=/work/cache/m2/repository"));
        assertTrue(agentCommand.contains("npm_config_cache=/work/cache/npm"));
    }

    @Test
    void shouldKeepAgentOutputAndCacheOutOfTheOracleWhileProvidingItsOwnCache() throws Exception {
        CapturingDockerRunner runner = new CapturingDockerRunner();
        DockerCodingBenchmarkExecutor executor = new DockerCodingBenchmarkExecutor(runner);
        CodingBenchmarkExecutionRequest request = request();

        executor.execute(request);

        List<String> agentCommand = runner.commands().stream()
                .filter(command -> command.contains("rd-eval-agent-case-01-a"))
                .findFirst()
                .orElseThrow();
        List<String> oracleCommand = runner.commands().stream()
                .filter(command -> command.contains("rd-eval-oracle-case-01-a"))
                .findFirst()
                .orElseThrow();
        assertTrue(agentCommand.toString().contains("dst=/work/output"));
        assertTrue(agentCommand.contains("RD_EVAL_CANDIDATE_PATCH=/work/output/candidate.patch"));
        assertTrue(oracleCommand.toString().contains(request.verifierCache().toString()));
        assertFalse(oracleCommand.toString().contains(
                "src=" + request.agentOutputDirectory() + ",dst=/work/output"
        ));
        assertFalse(oracleCommand.toString().contains(request.agentCache().toString()));
    }

    @Test
    void shouldFailClosedWhenTheAgentDoesNotPublishACandidatePatch() throws Exception {
        CapturingDockerRunner runner = new CapturingDockerRunner(false, false);
        DockerCodingBenchmarkExecutor executor = new DockerCodingBenchmarkExecutor(runner);
        CodingBenchmarkExecutionRequest request = request(false);

        CodingBenchmarkExecutionResult result = executor.execute(request);

        assertFalse(result.infrastructureFailure());
        assertEquals(0, result.agentExitCode());
        assertEquals(-1, result.oracleExitCode());
        assertTrue(result.errorMessage().contains("candidate patch"));
        assertFalse(runner.commands().stream().anyMatch(command -> command.contains("rd-eval-oracle-case-01-a")));
    }

    @Test
    void shouldQuarantineAStaleCandidatePatchBeforeTheAgentStarts() throws Exception {
        CapturingDockerRunner runner = new CapturingDockerRunner();
        DockerCodingBenchmarkExecutor executor = new DockerCodingBenchmarkExecutor(runner);
        CodingBenchmarkExecutionRequest request = request(true);

        CodingBenchmarkExecutionResult result = executor.execute(request);

        assertTrue(result.infrastructureFailure());
        assertTrue(result.errorMessage().contains("candidate patch output already exists"));
        assertFalse(runner.commands().stream().anyMatch(command -> command.contains("rd-eval-agent-case-01-a")));
    }

    private CodingBenchmarkExecutionRequest request() throws Exception {
        return request(false);
    }

    private CodingBenchmarkExecutionRequest request(boolean precreateCandidatePatch) throws Exception {
        Path agentRepository = Files.createDirectories(tempDir.resolve("agent/repo"));
        Path agentCache = Files.createDirectories(tempDir.resolve("agent/cache"));
        Path agentOutput = Files.createDirectories(tempDir.resolve("agent/output"));
        Path verifierRepository = Files.createDirectories(tempDir.resolve("verifier/repo"));
        Path verifierCache = Files.createDirectories(tempDir.resolve("verifier/cache"));
        Path protectedBundle = Files.createDirectories(tempDir.resolve("protected-tests"));
        Path output = Files.createDirectories(tempDir.resolve("output"));
        Path patch = agentOutput.resolve("candidate.patch");
        if (precreateCandidatePatch) {
            Files.writeString(patch, "diff --git a/a b/a\n");
        }
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
                agentOutput,
                verifierRepository,
                verifierCache,
                patch,
                protectedBundle,
                null,
                "tests/runtime_withheld",
                output,
                List.of("/opt/rd/agent-entrypoint"),
                List.of("/opt/rd/oracle-entrypoint"),
                "one-time-relay-token",
                45 * 60_000L,
                8 * 60_000L
        );
    }

    private CodingBenchmarkExecutionRequest runtimePatchRequest() throws Exception {
        Path agentRepository = Files.createDirectories(tempDir.resolve("runtime-agent/repo"));
        Path agentCache = Files.createDirectories(tempDir.resolve("runtime-agent/cache"));
        Path agentOutput = Files.createDirectories(tempDir.resolve("runtime-agent/output"));
        Path verifierRepository = Files.createDirectories(tempDir.resolve("runtime-verifier/repo"));
        Path verifierCache = Files.createDirectories(tempDir.resolve("runtime-verifier/cache"));
        Path output = Files.createDirectories(tempDir.resolve("runtime-output"));
        Path patch = agentOutput.resolve("candidate.patch");
        Path runtimePatch = tempDir.resolve("runtime-withheld.patch");
        Files.writeString(runtimePatch, "diff --git a/test/a b/test/a\\n");
        CodingBenchmarkTrial trial = CodingBenchmarkTrial.queued(
                "trial-2", "100", "case-01", CodingBenchmarkArm.A, 0, 100L
        );
        return new CodingBenchmarkExecutionRequest(
                trial,
                "registry.example/agent@sha256:" + "a".repeat(64),
                "registry.example/oracle@sha256:" + "b".repeat(64),
                agentRepository,
                agentCache,
                agentOutput,
                verifierRepository,
                verifierCache,
                patch,
                null,
                runtimePatch,
                "",
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
        private final boolean publishCandidatePatch;

        private CapturingDockerRunner() {
            this(false, true);
        }

        private CapturingDockerRunner(boolean failCleanup) {
            this(failCleanup, true);
        }

        private CapturingDockerRunner(boolean failCleanup, boolean publishCandidatePatch) {
            this.failCleanup = failCleanup;
            this.publishCandidatePatch = publishCandidatePatch;
        }

        @Override
        public DockerCodingBenchmarkExecutor.DockerCommandResult run(List<String> command, long timeoutMillis) throws java.io.IOException {
            commands.add(List.copyOf(command));
            if (publishCandidatePatch && command.contains("rd-eval-agent-case-01-a")) {
                String mount = command.stream()
                        .filter(value -> value.startsWith("type=bind,src=") && value.endsWith(",dst=/work/output"))
                        .findFirst()
                        .orElseThrow();
                String source = mount.substring("type=bind,src=".length(), mount.length() - ",dst=/work/output".length());
                Files.writeString(Path.of(source).resolve("candidate.patch"), "diff --git a/a b/a\\n");
            }
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
