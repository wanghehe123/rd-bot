package com.wish.rd.engine.requirement.verify;

import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.rag.runtime.model.RdRequirementTask;

import java.nio.file.Path;
import java.util.List;

/**
 * Resolves repository-relative paths changed by the candidate being verified.
 *
 * <p>Tests inject a fixed list (for example {@code README.md} or {@code src/App.tsx}).
 * Production can read the real git change-set from the prepared workspace.
 */
@FunctionalInterface
public interface HostVerificationChangeSetResolver {

    /**
     * Returns the candidate change-set used for docs-only classification and command detection.
     *
     * @param task        requirement task
     * @param codingStage coding stage being verified
     * @param workspace   prepared repository root
     * @return repository-relative paths; empty is undeterminable, not docs-only
     */
    List<String> resolve(RdRequirementTask task, AgentStageRun codingStage, Path workspace);
}
