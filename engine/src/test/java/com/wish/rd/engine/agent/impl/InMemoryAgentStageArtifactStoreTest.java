package com.wish.rd.engine.agent.impl;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryAgentStageArtifactStoreTest {

    private final InMemoryAgentStageArtifactStore store = new InMemoryAgentStageArtifactStore();

    @Test
    void saveImmutableShouldBeIdempotentForMatchingHash() {
        AgentStageArtifact artifact = artifact("7478000000000000201", "sha256:abc");
        AgentStageArtifact saved = store.saveImmutable(artifact);
        AgentStageArtifact again = store.saveImmutable(copy(artifact, "different preview", "sha256:abc"));

        assertSame(saved, again);
        assertEquals(1, store.listByTask(artifact.taskId()).size());
    }

    @Test
    void saveImmutableShouldRejectConflictingHash() {
        AgentStageArtifact artifact = artifact("7478000000000000201", "sha256:abc");
        store.saveImmutable(artifact);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> store.saveImmutable(copy(artifact, "different preview", "sha256:def"))
        );
        assertEquals("immutable artifact conflict: 7478000000000000201", error.getMessage());
    }

    private static AgentStageArtifact artifact(String artifactId, String contentHash) {
        return new AgentStageArtifact(
                artifactId,
                "7478000000000000101",
                "7478000000000000000",
                AgentRole.CODING_AGENT,
                "ROLE_EXECUTION_INPUT_MANIFEST",
                "rd-agent-stage://7478000000000000000/7478000000000000101/manifest",
                "manifest",
                "{\"version\":1}",
                contentHash,
                "{}",
                1_783_000_000_000L
        );
    }

    private static AgentStageArtifact copy(
            AgentStageArtifact artifact,
            String contentPreview,
            String contentHash
    ) {
        return new AgentStageArtifact(
                artifact.artifactId(),
                artifact.stageRunId(),
                artifact.taskId(),
                artifact.role(),
                artifact.artifactType(),
                artifact.artifactUri(),
                artifact.summary(),
                contentPreview,
                contentHash,
                artifact.metadataJson(),
                artifact.createdAtEpochMillis()
        );
    }
}
