package com.wish.rd.exec.repair.pi;

import com.wish.rd.exec.repair.pi.impl.InMemoryAgentPrivateArtifactIndex;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryAgentPrivateArtifactIndexTest {

    @Test
    void shouldIndexOnlyMetadataAndReturnExpiredObjectsInExpiryOrder() {
        InMemoryAgentPrivateArtifactIndex index = new InMemoryAgentPrivateArtifactIndex();
        AgentExecutionProfileSnapshot snapshot = snapshot();
        long now = System.currentTimeMillis();
        index.record(snapshot, artifact("session-1", now + 100L));
        index.record(snapshot, artifact("session-2", now + 50L));

        var expired = index.findExpired(now + 75L, 10);

        assertEquals(1, expired.size());
        assertEquals("private/session/session-2.jsonl", expired.getFirst().artifactName());
        index.remove(expired.getFirst().artifactId());
        var remaining = index.findExpired(now + 1000L, 10);
        assertEquals(1, remaining.size());
        index.remove(remaining.getFirst().artifactId());
        assertEquals(0, index.findExpired(now + 1000L, 10).size());
    }

    private static RepairArtifact artifact(String name, long expiresAt) {
        return new RepairArtifact(
                RepairArtifactType.PI_SESSION,
                "private/session/" + name + ".jsonl",
                "s3://rd-pi-session/" + name,
                "session",
                Map.of(
                        "restricted", "true",
                        "bytes", "10",
                        "sha256", "sha256:" + "a".repeat(64),
                        "contentType", "application/x-ndjson",
                        "retentionClass", "SESSION",
                        "expiresAtEpochMillis", String.valueOf(expiresAt)
                )
        );
    }

    private static AgentExecutionProfileSnapshot snapshot() {
        String json = "{\"runtimeType\":\"PI\"}";
        return new AgentExecutionProfileSnapshot(
                "snapshot-1", "stage-1", "task-1", "CODING_AGENT", 1,
                AgentRuntimeType.PI, json, AgentExecutionProfileSnapshot.sha256(json), 1L
        );
    }
}
