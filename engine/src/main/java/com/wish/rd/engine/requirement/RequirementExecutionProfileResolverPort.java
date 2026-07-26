package com.wish.rd.engine.requirement;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.model.RequirementExecutionProfileResolution;
import com.wish.rd.rag.runtime.model.RdRequirementTask;

/** Resolves and persists a stage's immutable execution profile before RUNNING. */
@FunctionalInterface
public interface RequirementExecutionProfileResolverPort {

    RequirementExecutionProfileResolution resolve(
            RdRequirementTask task,
            AgentRole role,
            String stageRunId,
            int attemptNo
    );

    static RequirementExecutionProfileResolverPort unavailable() {
        return (task, role, stageRunId, attemptNo) -> RequirementExecutionProfileResolution.none();
    }
}
