package com.wish.rd.engine.requirement.verify;

import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.requirement.model.AgentWorkflowPlan;
import com.wish.rd.engine.requirement.verify.model.HostVerificationRun;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;
import com.wish.rd.rag.runtime.model.RdRequirementTask;

/**
 * Executes one host BUILD/STATIC verification after Coding succeeds.
 *
 * <p>Called by {@link com.wish.rd.engine.requirement.RequirementAgentStageOrchestrator}
 * when the plan allows host-verify remediations. Engine code must not start
 * processes; bootstrap implements this port.
 */
@FunctionalInterface
public interface HostVerificationPort {

    /**
     * Creates a {@link HostVerificationRun} and executes BUILD then STATIC when required.
     *
     * @param task                         requirement task being verified
     * @param codingStage                  coding stage this run verifies; {@code taskId} must match
     * @param plan                         workflow plan; ignored by the executor besides existence
     * @param remediationCountAlreadyUsed  cheap remediations already consumed
     * @return persisted terminal (or skipped) verification snapshot
     * @throws IllegalArgumentException when task/stage are missing or belong to different tasks
     * @throws UnsupportedOperationException when this is the unavailable stub
     */
    HostVerificationRun verify(
            RdRequirementTask task,
            AgentStageRun codingStage,
            AgentWorkflowPlan plan,
            int remediationCountAlreadyUsed
    );

    /**
     * Fail-closed stub for tests and contexts that inject InMemory store plus a fake executor.
     *
     * @return port that rejects {@link #verify}
     */
    static HostVerificationPort unavailable() {
        return (task, codingStage, plan, remediationCountAlreadyUsed) -> {
            throw new UnsupportedOperationException(
                    "host verification executor is unavailable; inject InMemoryHostVerificationStore "
                            + "and a fake HostVerificationExecutorAdapter in tests");
        };
    }

    /**
     * Succeeding placeholder that the orchestrator must not invoke when host
     * verify is disabled. Production and D-plan tests inject a real or fake port.
     *
     * @return port that reports {@link HostVerificationStatus#SUCCEEDED}
     */
    static HostVerificationPort noop() {
        return (task, codingStage, plan, remediationCountAlreadyUsed) -> {
            if (task == null || codingStage == null) {
                throw new IllegalArgumentException("task and codingStage are required");
            }
            long now = System.currentTimeMillis();
            return new HostVerificationRun(
                    "host-verify-noop",
                    task.taskId(),
                    codingStage.stageRunId(),
                    "",
                    1,
                    HostVerificationStatus.SUCCEEDED,
                    false,
                    "",
                    "",
                    Math.max(0, remediationCountAlreadyUsed),
                    now,
                    now,
                    now
            );
        };
    }
}
