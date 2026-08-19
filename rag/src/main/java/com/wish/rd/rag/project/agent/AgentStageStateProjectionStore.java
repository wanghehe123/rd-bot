package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.model.AgentContextInjectionProjectionUpdate;
import com.wish.rd.rag.project.agent.model.AgentStageProjectionWriteResult;
import com.wish.rd.rag.project.agent.model.AgentStageStateIdentity;
import com.wish.rd.rag.project.agent.model.AgentStageStateProjection;
import com.wish.rd.rag.project.agent.model.AgentStateProjectionUpdate;

import java.util.Optional;

/** Persistence port for independently monotonic live state and injection projections. */
public interface AgentStageStateProjectionStore {
    AgentStageProjectionWriteResult projectState(AgentStateProjectionUpdate update);

    AgentStageProjectionWriteResult projectInjection(AgentContextInjectionProjectionUpdate update);

    AgentStageProjectionWriteResult finalizeProjection(AgentStageStateIdentity identity, long finalizedAtEpochMillis);

    Optional<AgentStageStateProjection> findByStageRunId(String stageRunId);
}
