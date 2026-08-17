package com.wish.rd.bootstrap.verify;

import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.rag.runtime.model.RdRequirementTask;

/**
 * Loads the Coding stage's verified {@code candidate-patch.diff} for host verification replay.
 *
 * <p>Tests inject a fake. Production reads the persisted PATCH_DIFF / handoff JSON and
 * rematerializes the object through the same integrity checks as local QA.
 */
@FunctionalInterface
public interface HostVerificationPatchSource {

    /**
     * Returns the verified candidate patch for {@code codingStage}.
     *
     * @param task        requirement task
     * @param codingStage coding stage being verified
     * @return patch bytes; never {@code null}
     * @throws IllegalStateException when the coding stage has no verified candidate patch
     */
    HostVerificationCandidatePatch load(RdRequirementTask task, AgentStageRun codingStage);
}
