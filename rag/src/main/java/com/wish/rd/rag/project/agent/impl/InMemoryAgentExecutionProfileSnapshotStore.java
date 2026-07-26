package com.wish.rd.rag.project.agent.impl;

import com.wish.rd.rag.project.agent.AgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory append-only snapshot storage for focused tests and local runs. */
public final class InMemoryAgentExecutionProfileSnapshotStore implements AgentExecutionProfileSnapshotStore {

    private final ConcurrentMap<String, AgentExecutionProfileSnapshot> values = new ConcurrentHashMap<>();

    @Override
    public AgentExecutionProfileSnapshot saveIfAbsent(AgentExecutionProfileSnapshot snapshot) {
        return values.putIfAbsent(snapshot.stageRunId(), snapshot) == null
                ? snapshot
                : values.get(snapshot.stageRunId());
    }

    @Override
    public Optional<AgentExecutionProfileSnapshot> findByStageRunId(String stageRunId) {
        return Optional.ofNullable(values.get(stageRunId == null ? "" : stageRunId.strip()));
    }

    @Override
    public Optional<AgentExecutionProfileSnapshot> findBySnapshotId(String snapshotId) {
        String normalized = snapshotId == null ? "" : snapshotId.strip();
        return values.values().stream()
                .filter(snapshot -> snapshot.snapshotId().equals(normalized))
                .findFirst();
    }
}
