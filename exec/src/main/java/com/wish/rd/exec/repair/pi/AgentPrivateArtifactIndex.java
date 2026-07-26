package com.wish.rd.exec.repair.pi;

import com.wish.rd.exec.repair.pi.model.AgentPrivateArtifactRecord;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;

import java.util.List;

/** Durable metadata index for restricted Pi objects; it never stores object content. */
public interface AgentPrivateArtifactIndex {

    void record(AgentExecutionProfileSnapshot snapshot, RepairArtifact artifact);

    List<AgentPrivateArtifactRecord> findExpired(long nowEpochMillis, int limit);

    void remove(String artifactId);

    static AgentPrivateArtifactIndex noop() {
        return new AgentPrivateArtifactIndex() {
            @Override
            public void record(AgentExecutionProfileSnapshot snapshot, RepairArtifact artifact) {
            }

            @Override
            public List<AgentPrivateArtifactRecord> findExpired(long nowEpochMillis, int limit) {
                return List.of();
            }

            @Override
            public void remove(String artifactId) {
            }
        };
    }
}
