package com.wish.rd.bootstrap.evaluation.impl;

import com.wish.rd.engine.evaluation.model.CodingBenchmarkArm;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionRequest;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionResult;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeCodingBenchmarkExecutionPortTest {

    @Test
    void shouldReturnADeterministicPassWithoutDockerOrWorkspaceFiles() {
        FakeCodingBenchmarkExecutionPort fake = new FakeCodingBenchmarkExecutionPort();
        AtomicInteger hookCalls = new AtomicInteger();
        CodingBenchmarkExecutionRequest request = request("100", "case-01");

        CodingBenchmarkExecutionResult result = fake.execute(request, hookCalls::incrementAndGet);

        assertEquals(1, hookCalls.get());
        assertFalse(result.infrastructureFailure());
        assertEquals(0, result.agentExitCode());
        assertEquals(0, result.oracleExitCode());
        assertEquals("", result.errorMessage());
        assertEquals("trial-1", result.attestation().trialId());
        // The rehearsal never isolated a container, so its attestation must not claim it did.
        assertFalse(result.attestation().agentNetworkInternal());
        assertFalse(Files.exists(request.outputDirectory()));
        assertFalse(Files.exists(request.candidatePatch()));
    }

    @Test
    void shouldKeepTheTrialInFlightLongEnoughForAControlPlaneAction() {
        FakeCodingBenchmarkExecutionPort fake = new FakeCodingBenchmarkExecutionPort();

        long startedAt = System.nanoTime();
        fake.execute(request("100", "case-01"));
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertTrue(elapsedMillis >= FakeCodingBenchmarkExecutionPort.DEFAULT_PHASE_DELAY_MILLIS,
                "fake execution finished in " + elapsedMillis + "ms");
    }

    @Test
    void shouldRepeatTheSameVerdictForTheSameTrial() {
        FakeCodingBenchmarkExecutionPort fake = new FakeCodingBenchmarkExecutionPort(0L);

        CodingBenchmarkExecutionResult first = fake.execute(request("100", "case-01"));
        CodingBenchmarkExecutionResult second = fake.execute(request("100", "case-01"));

        assertEquals(first.agentExitCode(), second.agentExitCode());
        assertEquals(first.oracleExitCode(), second.oracleExitCode());
        assertEquals(first.infrastructureFailure(), second.infrastructureFailure());
    }

    @Test
    void shouldSettleWithoutTheOracleHookWhenTheCampaignIsAlreadyCancelled() {
        FakeCodingBenchmarkExecutionPort fake = new FakeCodingBenchmarkExecutionPort();
        AtomicInteger hookCalls = new AtomicInteger();
        fake.cancelCampaign("100");

        CodingBenchmarkExecutionResult result = fake.execute(request("100", "case-01"), hookCalls::incrementAndGet);

        assertTrue(fake.isCampaignCancelled("100"));
        assertEquals(0, hookCalls.get());
        assertTrue(result.infrastructureFailure());
        assertEquals(FakeCodingBenchmarkExecutionPort.CANCELLED_MESSAGE, result.errorMessage());
    }

    @Test
    void shouldStopCooperativelyWhenTheCampaignIsCancelledDuringTheOraclePhase() {
        FakeCodingBenchmarkExecutionPort fake = new FakeCodingBenchmarkExecutionPort();
        AtomicInteger hookCalls = new AtomicInteger();

        CodingBenchmarkExecutionResult result = fake.execute(request("100", "case-01"), () -> {
            hookCalls.incrementAndGet();
            fake.cancelCampaign("100");
        });

        assertEquals(1, hookCalls.get());
        assertTrue(result.infrastructureFailure());
        assertEquals(0, result.agentExitCode());
        assertEquals(FakeCodingBenchmarkExecutionPort.CANCELLED_MESSAGE, result.errorMessage());
    }

    @Test
    void shouldCancelOnlyTheRequestedCampaignAndReleaseItWhenCleared() {
        FakeCodingBenchmarkExecutionPort fake = new FakeCodingBenchmarkExecutionPort(0L);
        fake.cancelCampaign("100");

        CodingBenchmarkExecutionResult cancelled = fake.execute(request("100", "case-01"));
        CodingBenchmarkExecutionResult other = fake.execute(request("200", "case-01"));
        fake.clearCampaign("100");
        CodingBenchmarkExecutionResult restarted = fake.execute(request("100", "case-01"));

        assertTrue(cancelled.infrastructureFailure());
        assertFalse(other.infrastructureFailure());
        assertFalse(fake.isCampaignCancelled("100"));
        assertFalse(restarted.infrastructureFailure());
    }

    @Test
    void shouldForceATestFailureOnlyForTheRegisteredCase() {
        FakeCodingBenchmarkExecutionPort fake = new FakeCodingBenchmarkExecutionPort(0L);
        fake.failCase("case-02");

        CodingBenchmarkExecutionResult failing = fake.execute(request("100", "case-02"));
        CodingBenchmarkExecutionResult passing = fake.execute(request("100", "case-01"));

        assertFalse(failing.infrastructureFailure());
        assertEquals(0, failing.agentExitCode());
        assertEquals(1, failing.oracleExitCode());
        assertTrue(failing.errorMessage().contains("case-02"));
        assertEquals(0, passing.oracleExitCode());
    }

    private static CodingBenchmarkExecutionRequest request(String campaignId, String caseId) {
        // Paths intentionally stay unmaterialised: the fake executor must not require a workspace.
        Path workspace = Path.of("target", "fake-coding-benchmark", campaignId, caseId).toAbsolutePath();
        Path agentOutput = workspace.resolve("agent/output");
        CodingBenchmarkTrial trial = CodingBenchmarkTrial.queued(
                "trial-1", campaignId, caseId, CodingBenchmarkArm.A, 0, 100L
        );
        return new CodingBenchmarkExecutionRequest(
                trial,
                "registry.example/agent@sha256:" + "a".repeat(64),
                "registry.example/oracle@sha256:" + "b".repeat(64),
                workspace.resolve("agent/repo"),
                workspace.resolve("agent/cache"),
                agentOutput,
                workspace.resolve("verifier/repo"),
                workspace.resolve("verifier/cache"),
                agentOutput.resolve("candidate.patch"),
                workspace.resolve("protected-tests"),
                null,
                "tests/runtime_withheld",
                workspace.resolve("output"),
                List.of("/opt/rd/agent-entrypoint"),
                List.of("/opt/rd/oracle-entrypoint"),
                "one-time-relay-token",
                45 * 60_000L,
                8 * 60_000L
        );
    }
}
