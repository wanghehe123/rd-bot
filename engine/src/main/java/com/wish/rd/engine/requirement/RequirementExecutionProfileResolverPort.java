package com.wish.rd.engine.requirement;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.model.RequirementExecutionProfileResolution;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeCapability;
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

    default AgentExecutionProfileSnapshot prepareSnapshot(
            RdRequirementTask task,
            AgentRole role,
            String stageRunId,
            int attemptNo
    ) {
        return prepareSnapshot(task, role, stageRunId, attemptNo, null);
    }

    /** Prepares an immutable future-stage snapshot without creating a persistence row. */
    default AgentExecutionProfileSnapshot prepareSnapshot(
            RdRequirementTask task,
            AgentRole role,
            String stageRunId,
            int attemptNo,
            AgentRuntimeCapability requiredCapability
    ) {
        throw new IllegalStateException("side-effect-free execution profile preparation is unavailable");
    }

    static RequirementExecutionProfileResolverPort unavailable() {
        return (task, role, stageRunId, attemptNo) -> RequirementExecutionProfileResolution.none();
    }
}
