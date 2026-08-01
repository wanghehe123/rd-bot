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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
        assertTrue(
                oracleCommand.stream().anyMatch(arg -> arg.contains("rd_eval_oracle.py")),
                "oracle must mount the host verifier script into toolchain images"
        );
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
        assertTrue(agentCommand.contains("MAVEN_OPTS=-Dmaven.repo.local=/work/cache/m2 -Dfile.encoding=UTF-8"));
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
    void shouldPromotePatchDiffWhenCandidatePatchIsMissing() throws Exception {
        CapturingDockerRunner runner = new CapturingDockerRunner(false, false, false, true);
        DockerCodingBenchmarkExecutor executor = new DockerCodingBenchmarkExecutor(runner);
        CodingBenchmarkExecutionRequest request = request(false);

        CodingBenchmarkExecutionResult result = executor.execute(request);

        assertFalse(result.infrastructureFailure());
        assertTrue(Files.isRegularFile(request.candidatePatch()));
        assertTrue(Files.size(request.candidatePatch()) > 0);
        assertTrue(started(runner, "rd-eval-oracle-case-01-a"));
        assertTrue(Files.readString(request.agentOutputDirectory().resolve("candidate-patch-source.json"))
                .contains("PROMOTED_PATCH_DIFF"));
    }

    @Test
    void shouldStillRunOracleWhenAgentExitsUncleanlyButPublishedAPatch() throws Exception {
        CapturingDockerRunner runner = new CapturingDockerRunner(false, true, false, false, true);
        DockerCodingBenchmarkExecutor executor = new DockerCodingBenchmarkExecutor(runner);
        CodingBenchmarkExecutionRequest request = request(false);

        CodingBenchmarkExecutionResult result = executor.execute(request);

        assertFalse(result.infrastructureFailure());
        assertEquals(1, result.agentExitCode());
        assertTrue(Files.isRegularFile(request.candidatePatch()));
        assertTrue(started(runner, "rd-eval-oracle-case-01-a"));
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

    @Test
    void shouldSignalTheOracleHookAfterAgentWorkAndBeforeTheOracleContainer() throws Exception {
        CapturingDockerRunner runner = new CapturingDockerRunner();
        DockerCodingBenchmarkExecutor executor = new DockerCodingBenchmarkExecutor(runner);
        AtomicInteger hookCalls = new AtomicInteger();
        AtomicBoolean agentFinishedWhenHookRan = new AtomicBoolean();
        AtomicBoolean oracleStartedWhenHookRan = new AtomicBoolean(true);

        CodingBenchmarkExecutionResult result = executor.execute(request(), () -> {
            hookCalls.incrementAndGet();
            agentFinishedWhenHookRan.set(started(runner, "rd-eval-agent-case-01-a"));
            oracleStartedWhenHookRan.set(started(runner, "rd-eval-oracle-case-01-a"));
        });

        assertEquals(1, hookCalls.get());
        assertTrue(agentFinishedWhenHookRan.get());
        assertFalse(oracleStartedWhenHookRan.get());
        assertFalse(result.infrastructureFailure());
        assertTrue(started(runner, "rd-eval-oracle-case-01-a"));
    }

    @Test
    void shouldNotSignalTheOracleHookWhenTheAgentDoesNotPublishACandidatePatch() throws Exception {
        CapturingDockerRunner runner = new CapturingDockerRunner(false, false);
        DockerCodingBenchmarkExecutor executor = new DockerCodingBenchmarkExecutor(runner);
        AtomicInteger hookCalls = new AtomicInteger();

        CodingBenchmarkExecutionResult result = executor.execute(request(false), hookCalls::incrementAndGet);

        assertEquals(0, hookCalls.get());
        assertTrue(result.errorMessage().contains("candidate patch"));
    }

    @Test
    void shouldNotSignalTheOracleHookWhenTheTrialIsQuarantinedBeforeTheAgentStarts() throws Exception {
        CapturingDockerRunner runner = new CapturingDockerRunner();
        DockerCodingBenchmarkExecutor executor = new DockerCodingBenchmarkExecutor(runner);
        AtomicInteger hookCalls = new AtomicInteger();

        CodingBenchmarkExecutionResult result = executor.execute(request(true), hookCalls::incrementAndGet);

        assertEquals(0, hookCalls.get());
        assertTrue(result.infrastructureFailure());
    }

    private static boolean started(CapturingDockerRunner runner, String containerName) {
        return runner.commands().stream().anyMatch(command -> command.contains(containerName));
    }

    private CodingBenchmarkExecutionRequest request() throws Exception {
        return request(false);
    }

    private CodingBenchmarkExecutionRequest request(boolean precreateCandidatePatch) throws Exception {
        Path trialRoot = tempDir.resolve("trial");
        Path agentRepository = Files.createDirectories(trialRoot.resolve("repo"));
        Path agentCache = Files.createDirectories(trialRoot.resolve("cache"));
        Path agentOutput = Files.createDirectories(trialRoot.resolve("output"));
        Path agentInput = Files.createDirectories(trialRoot.resolve("input"));
        Files.writeString(agentInput.resolve("request.json"), "{\"protocol\":\"rd-pi-request/v1\"}\n");
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
        Path trialRoot = tempDir.resolve("runtime-trial");
        Path agentRepository = Files.createDirectories(trialRoot.resolve("repo"));
        Path agentCache = Files.createDirectories(trialRoot.resolve("cache"));
        Path agentOutput = Files.createDirectories(trialRoot.resolve("output"));
        Files.createDirectories(trialRoot.resolve("input"));
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

    @Test
    void shouldStartRelayBeforeAgentAndRemoveItDuringCleanup() throws Exception {
        CapturingDockerRunner runner = new CapturingDockerRunner();
        DockerCodingBenchmarkExecutor executor = new DockerCodingBenchmarkExecutor(runner);

        executor.execute(request());

        assertTrue(runner.commands().stream().anyMatch(command -> command.contains("rd-eval-relay-case-01-a")));
        assertTrue(runner.commands().stream().anyMatch(command ->
                command.contains("docker") && command.contains("rm") && command.contains("-f") && command.contains("rd-eval-relay-case-01-a")));
        List<String> agentIndex = runner.commands().stream()
                .filter(command -> command.contains("rd-eval-agent-case-01-a"))
                .findFirst()
                .orElseThrow();
        List<String> relayRun = runner.commands().stream()
                .filter(command -> command.contains("rd-eval-relay-case-01-a") && command.contains("run") && command.contains("-d"))
                .findFirst()
                .orElseThrow();
        List<String> egressConnect = runner.commands().stream()
                .filter(command -> command.contains("network") && command.contains("connect") && command.contains("bridge"))
                .findFirst()
                .orElseThrow();
        assertEquals(1, relayRun.stream().filter("--network"::equals).count());
        assertTrue(relayRun.contains("rd-eval-network-case-01-a"));
        assertFalse(relayRun.contains("bridge"));
        assertEquals(List.of("docker", "network", "connect", "bridge", "rd-eval-relay-case-01-a"), egressConnect);
        assertTrue(runner.commands().indexOf(relayRun) < runner.commands().indexOf(egressConnect));
        assertTrue(runner.commands().indexOf(egressConnect) < runner.commands().indexOf(agentIndex));
        assertTrue(agentIndex.toString().contains("dst=/work/input,readonly"));
    }

    @Test
    void shouldQuarantineWhenRelayEgressConnectFails() throws Exception {
        CapturingDockerRunner runner = new CapturingDockerRunner(false, true, true);
        DockerCodingBenchmarkExecutor executor = new DockerCodingBenchmarkExecutor(runner);
        CodingBenchmarkExecutionRequest request = request();

        CodingBenchmarkExecutionResult result = executor.execute(request);

        assertTrue(result.infrastructureFailure());
        assertTrue(result.errorMessage().contains("relay container egress connect failed"));
        assertTrue(result.errorMessage().contains("network connect denied"));
        assertFalse(runner.commands().stream().anyMatch(command -> command.contains("rd-eval-agent-case-01-a")));
        assertTrue(runner.commands().stream().anyMatch(command ->
                command.contains("docker") && command.contains("rm") && command.contains("-f") && command.contains("rd-eval-relay-case-01-a")));
    }

    private static final class CapturingDockerRunner implements DockerCodingBenchmarkExecutor.DockerCommandRunner {
        private final List<List<String>> commands = new ArrayList<>();
        private final boolean failCleanup;
        private final boolean publishCandidatePatch;
        private final boolean failRelayEgressConnect;
        private final boolean publishPatchDiff;
        private final boolean failAgent;

        private CapturingDockerRunner() {
            this(false, true, false, false, false);
        }

        private CapturingDockerRunner(boolean failCleanup) {
            this(failCleanup, true, false, false, false);
        }

        private CapturingDockerRunner(boolean failCleanup, boolean publishCandidatePatch) {
            this(failCleanup, publishCandidatePatch, false, false, false);
        }

        private CapturingDockerRunner(boolean failCleanup, boolean publishCandidatePatch, boolean failRelayEgressConnect) {
            this(failCleanup, publishCandidatePatch, failRelayEgressConnect, false, false);
        }

        private CapturingDockerRunner(
                boolean failCleanup,
                boolean publishCandidatePatch,
                boolean failRelayEgressConnect,
                boolean publishPatchDiff
        ) {
            this(failCleanup, publishCandidatePatch, failRelayEgressConnect, publishPatchDiff, false);
        }

        private CapturingDockerRunner(
                boolean failCleanup,
                boolean publishCandidatePatch,
                boolean failRelayEgressConnect,
                boolean publishPatchDiff,
                boolean failAgent
        ) {
            this.failCleanup = failCleanup;
            this.publishCandidatePatch = publishCandidatePatch;
            this.failRelayEgressConnect = failRelayEgressConnect;
            this.publishPatchDiff = publishPatchDiff;
            this.failAgent = failAgent;
        }

        @Override
        public DockerCodingBenchmarkExecutor.DockerCommandResult run(List<String> command, long timeoutMillis) throws java.io.IOException {
            commands.add(List.copyOf(command));
            if ((publishCandidatePatch || publishPatchDiff) && command.contains("rd-eval-agent-case-01-a")) {
                String mount = command.stream()
                        .filter(value -> value.startsWith("type=bind,src=") && value.endsWith(",dst=/work/output"))
                        .findFirst()
                        .orElseThrow();
                String source = mount.substring("type=bind,src=".length(), mount.length() - ",dst=/work/output".length());
                if (publishCandidatePatch) {
                    Files.writeString(Path.of(source).resolve("candidate.patch"), "diff --git a/a b/a\\n");
                }
                if (publishPatchDiff) {
                    Files.writeString(Path.of(source).resolve("patch.diff"), "diff --git a/from-bridge b/from-bridge\n");
                }
                if (failAgent) {
                    return new DockerCodingBenchmarkExecutor.DockerCommandResult(1, "", "agent exited uncleanly");
                }
            }
            if (failCleanup && command.size() >= 3 && command.get(1).equals("network") && command.get(2).equals("rm")) {
                return new DockerCodingBenchmarkExecutor.DockerCommandResult(1, "", "cleanup failed");
            }
            if (command.contains("rd-eval-relay-case-01-a") && command.contains("run") && command.contains("-d")) {
                return new DockerCodingBenchmarkExecutor.DockerCommandResult(0, "", "");
            }
            if (failRelayEgressConnect
                    && command.size() >= 5
                    && command.get(1).equals("network")
                    && command.get(2).equals("connect")
                    && command.get(3).equals("bridge")) {
                return new DockerCodingBenchmarkExecutor.DockerCommandResult(1, "", "network connect denied");
            }
            return new DockerCodingBenchmarkExecutor.DockerCommandResult(0, "", "");
        }

        List<List<String>> commands() {
            return List.copyOf(commands);
        }
    }
}
