package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;

import java.util.Optional;

/** Append-only persistence port for stage execution profile snapshots. */
public interface AgentExecutionProfileSnapshotStore {

    AgentExecutionProfileSnapshot saveIfAbsent(AgentExecutionProfileSnapshot snapshot);

    Optional<AgentExecutionProfileSnapshot> findByStageRunId(String stageRunId);

    Optional<AgentExecutionProfileSnapshot> findBySnapshotId(String snapshotId);
}
