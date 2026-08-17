package com.wish.rd.engine.requirement.verify;

import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.rag.runtime.model.RdRequirementTask;

import java.nio.file.Path;

/**
 * Prepares a repository checkout that already contains the candidate change-set.
 *
 * <p>Unit tests supply a temp directory. Production may wrap
 * {@code CleanHostVerifierWorkspaceFactory} without exposing Docker to those tests.
 */
@FunctionalInterface
public interface HostVerificationWorkspaceFactory {

    /**
     * Returns the repository root that host BUILD/STATIC should execute in.
     *
     * @param task        requirement task
     * @param codingStage coding stage whose candidate is already applied
     * @return existing repository path; never {@code null}
     */
    Path prepare(RdRequirementTask task, AgentStageRun codingStage);
}
