package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.rag.ingestion.impl.InMemoryObjectStorageService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QaEvidenceRetentionServiceTest {

    @Test
    void shouldDeletePrivateQaObjectsAndQaArtifactRowsForDeletedTask() {
        InMemoryObjectStorageService objectStorage = new InMemoryObjectStorageService();
        byte[] screenshot = "screenshot".getBytes(StandardCharsets.UTF_8);
        String uri = objectStorage.upload(
                "rd-qa-evidence",
                new ByteArrayInputStream(screenshot),
                screenshot.length,
                "current.png",
                "image/png"
        ).url();
        InMemoryAgentStageArtifactStore artifactStore = new InMemoryAgentStageArtifactStore();
        artifactStore.save(new AgentStageArtifact(
                "artifact-1",
                "stage-1",
                "task-1",
                AgentRole.QA_AGENT,
                "QA_SCREENSHOT",
                uri,
                "current screenshot",
                "",
                "sha256:test",
                "{}",
                1L
        ));

        QaEvidenceRetentionService service = new QaEvidenceRetentionService(artifactStore, objectStorage);

        assertEquals(1, service.deleteForTask("task-1"));
        assertEquals(0, artifactStore.listByTask("task-1").size());
        assertThrows(IllegalArgumentException.class, () -> objectStorage.openStream(uri));
    }
}
