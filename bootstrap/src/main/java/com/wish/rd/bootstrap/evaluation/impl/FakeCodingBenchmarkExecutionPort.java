package com.wish.rd.bootstrap.evaluation.impl;

import com.wish.rd.engine.evaluation.CodingBenchmarkExecutionHooks;
import com.wish.rd.engine.evaluation.CodingBenchmarkExecutionPort;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionRequest;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionResult;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkRuntimeAttestation;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Container-free {@link CodingBenchmarkExecutionPort} that lets the coding benchmark control plane
 * (dispatch, pause, resume, cancel and restart recovery) be rehearsed locally.
 *
 * <p>Registered by {@link com.wish.rd.bootstrap.evaluation.CodingBenchmarkExecutionConfiguration}
 * only when {@code rd.evaluation.coding-benchmark.executor=fake}; production keeps
 * {@link DockerCodingBenchmarkExecutor}. The port touches no Docker daemon, no network, no model
 * relay and no file in the trial workspace, so tests need neither prepared images nor credentials.
 *
 * <p>Outcomes are deterministic: every trial passes unless its case was registered through
 * {@link #failCase(String)}. Each phase pauses briefly so a claimed trial stays observably in
 * flight, which is what makes pause and cancel races reproducible.
 */
public final class FakeCodingBenchmarkExecutionPort implements CodingBenchmarkExecutionPort {

    /** Phase delay that keeps a trial in flight long enough for a control-plane action to land. */
    public static final long DEFAULT_PHASE_DELAY_MILLIS = 20L;

    static final String CANCELLED_MESSAGE = "coding benchmark campaign cancelled before the trial finished";
    static final String INTERRUPTED_MESSAGE = "fake coding benchmark execution was interrupted";

    private static final long MAX_PHASE_DELAY_MILLIS = 5_000L;

    private final Set<String> cancelledCampaigns = ConcurrentHashMap.newKeySet();
    private final Set<String> failingCaseIds = ConcurrentHashMap.newKeySet();
    private final long phaseDelayMillis;

    /** Creates a fake executor whose agent and oracle phases each take {@value #DEFAULT_PHASE_DELAY_MILLIS} ms. */
    public FakeCodingBenchmarkExecutionPort() {
        this(DEFAULT_PHASE_DELAY_MILLIS);
    }

    /**
     * Creates a fake executor with an explicit phase delay.
     *
     * @param phaseDelayMillis delay of each simulated phase, clamped to {@code [0, 5000]} ms so a
     *                         misconfigured value cannot stall a dispatcher test
     */
    public FakeCodingBenchmarkExecutionPort(long phaseDelayMillis) {
        this.phaseDelayMillis = Math.max(0L, Math.min(MAX_PHASE_DELAY_MILLIS, phaseDelayMillis));
    }

    /**
     * Runs a stub agent phase, reports the oracle boundary, then runs a stub oracle phase.
     *
     * @param request prepared trial contract; only the trial identity is read
     * @param hooks phase callbacks; {@code beforeOracle()} is invoked exactly once and only when the
     *              stub agent phase completed, matching the Docker executor's contract
     * @return a deterministic PASS, a forced test failure, or an infrastructure failure when the
     *         campaign was cancelled or the worker thread was interrupted
     */
    @Override
    public CodingBenchmarkExecutionResult execute(
            CodingBenchmarkExecutionRequest request,
            CodingBenchmarkExecutionHooks hooks
    ) {
        Objects.requireNonNull(request, "coding benchmark request must not be null");
        CodingBenchmarkExecutionHooks phaseHooks = hooks == null ? CodingBenchmarkExecutionHooks.noop() : hooks;
        CodingBenchmarkTrial trial = request.trial();
        CodingBenchmarkRuntimeAttestation attestation = attestation(request);

        PhaseOutcome agentPhase = runPhase(trial.campaignId());
        if (agentPhase != PhaseOutcome.COMPLETED) {
            return stopped(agentPhase, -1, -1, attestation);
        }
        // Only a completed agent phase reaches the oracle, so the dispatcher may hold this trial
        // here until an oracle slot frees.
        phaseHooks.beforeOracle();
        PhaseOutcome oraclePhase = runPhase(trial.campaignId());
        if (oraclePhase != PhaseOutcome.COMPLETED) {
            return stopped(oraclePhase, 0, -1, attestation);
        }
        boolean forcedFailure = failingCaseIds.contains(trial.caseId());
        return new CodingBenchmarkExecutionResult(
                0,
                forcedFailure ? 1 : 0,
                false,
                forcedFailure ? "fake oracle reported a forced test failure for case " + trial.caseId() : "",
                attestation
        );
    }

    /**
     * Signals every in-flight and future trial of one campaign to stop cooperatively.
     *
     * @param campaignId campaign whose trials must settle instead of finishing their phases
     */
    public void cancelCampaign(String campaignId) {
        String normalized = safe(campaignId);
        if (!normalized.isEmpty()) {
            cancelledCampaigns.add(normalized);
        }
    }

    /**
     * Drops the cancel signal of a settled campaign so a long-lived local process does not keep it.
     *
     * @param campaignId campaign whose trials have all reached a terminal state
     */
    public void clearCampaign(String campaignId) {
        cancelledCampaigns.remove(safe(campaignId));
    }

    /**
     * @param campaignId campaign identifier used by the trials
     * @return whether the campaign carries a cooperative cancel signal
     */
    public boolean isCampaignCancelled(String campaignId) {
        return cancelledCampaigns.contains(safe(campaignId));
    }

    /**
     * Forces a deterministic oracle test failure for one case, so scheduling tests can cover the
     * mixed pass/fail path without a container.
     *
     * @param caseId frozen benchmark case identifier
     */
    public void failCase(String caseId) {
        String normalized = safe(caseId);
        if (!normalized.isEmpty()) {
            failingCaseIds.add(normalized);
        }
    }

    private PhaseOutcome runPhase(String campaignId) {
        if (cancelledCampaigns.contains(campaignId)) {
            return PhaseOutcome.CANCELLED;
        }
        try {
            Thread.sleep(phaseDelayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return PhaseOutcome.INTERRUPTED;
        }
        // Cancel is cooperative: the flag is re-read once the simulated phase work has elapsed.
        return cancelledCampaigns.contains(campaignId) ? PhaseOutcome.CANCELLED : PhaseOutcome.COMPLETED;
    }

    private static CodingBenchmarkExecutionResult stopped(
            PhaseOutcome outcome,
            int agentExitCode,
            int oracleExitCode,
            CodingBenchmarkRuntimeAttestation attestation
    ) {
        String message = outcome == PhaseOutcome.CANCELLED ? CANCELLED_MESSAGE : INTERRUPTED_MESSAGE;
        return new CodingBenchmarkExecutionResult(agentExitCode, oracleExitCode, true, message, attestation);
    }

    /**
     * Records the requested digests but reports the fake network modes, so a rehearsal result can
     * never be mistaken for evidence that isolated containers actually ran.
     */
    private static CodingBenchmarkRuntimeAttestation attestation(CodingBenchmarkExecutionRequest request) {
        return new CodingBenchmarkRuntimeAttestation(
                request.trial().trialId(),
                request.agentImageDigest(),
                request.oracleImageDigest(),
                "fake",
                "fake",
                false,
                Instant.now().toEpochMilli()
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    /** Result of one simulated phase, used to keep cancel and interruption handling explicit. */
    private enum PhaseOutcome {
        COMPLETED,
        CANCELLED,
        INTERRUPTED
    }
}
