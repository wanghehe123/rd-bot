package com.wish.rd.exec.repair.pi.impl;

import com.wish.rd.exec.repair.pi.AgentPrivateArtifactIndex;
import com.wish.rd.exec.repair.pi.model.AgentPrivateArtifactRecord;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Small local index used by memory mode and tests; production uses the PostgreSQL adapter. */
public final class InMemoryAgentPrivateArtifactIndex implements AgentPrivateArtifactIndex {

    private final ConcurrentMap<String, AgentPrivateArtifactRecord> records = new ConcurrentHashMap<>();

    @Override
    public void record(AgentExecutionProfileSnapshot snapshot, RepairArtifact artifact) {
        AgentPrivateArtifactRecord record = AgentPrivateArtifactRecord.from(snapshot, artifact);
        records.put(record.artifactId(), record);
    }

    @Override
    public List<AgentPrivateArtifactRecord> findExpired(long nowEpochMillis, int limit) {
        int boundedLimit = Math.max(1, Math.min(1000, limit <= 0 ? 100 : limit));
        return records.values().stream()
                .filter(record -> record.expiresAtEpochMillis() <= nowEpochMillis)
                .sorted(Comparator.comparingLong(AgentPrivateArtifactRecord::expiresAtEpochMillis))
                .limit(boundedLimit)
                .toList();
    }

    @Override
    public void remove(String artifactId) {
        if (artifactId != null) records.remove(artifactId.strip());
    }
}
