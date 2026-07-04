package com.wish.rd.bootstrap.persistence;

import com.wish.rd.bootstrap.persistence.entity.RdAgentStageArtifactRow;
import com.wish.rd.bootstrap.persistence.mapper.RdAgentStageArtifactMapper;
import com.wish.rd.engine.agent.AgentRole;
import com.wish.rd.engine.agent.AgentStageArtifact;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresAgentStageArtifactStoreTest {

    private final RdAgentStageArtifactMapper mapper = mock(RdAgentStageArtifactMapper.class);
    private final PostgresAgentStageArtifactStore store = new PostgresAgentStageArtifactStore(mapper);

    @Test
    void shouldSaveAndListStageArtifacts() {
        AgentStageArtifact artifact = new AgentStageArtifact(
                "7478000000000000201",
                "7478000000000000101",
                "7478000000000000000",
                AgentRole.CODING_AGENT,
                "RESULT_JSON",
                "rd-agent-stage://7478000000000000000/7478000000000000101/result",
                "CODING_AGENT result json",
                "{\"success\":true,\"summary\":\"done\"}",
                "sha256:abc",
                "{\"contentLength\":42}",
                1_783_000_000_000L
        );
        when(mapper.selectList(any())).thenReturn(List.of(row(artifact)));

        store.save(artifact);

        ArgumentCaptor<RdAgentStageArtifactRow> rowCaptor = ArgumentCaptor.forClass(RdAgentStageArtifactRow.class);
        verify(mapper).upsertStageArtifact(rowCaptor.capture());
        RdAgentStageArtifactRow saved = rowCaptor.getValue();
        assertEquals(7478000000000000201L, saved.id);
        assertEquals(7478000000000000101L, saved.stageRunId);
        assertEquals(7478000000000000000L, saved.taskId);
        assertEquals("CODING_AGENT", saved.role);
        assertEquals("RESULT_JSON", saved.artifactType);
        assertEquals("{\"success\":true,\"summary\":\"done\"}", saved.contentPreview);
        assertEquals("sha256:abc", saved.contentHash);
        assertEquals(List.of(artifact), store.listByTask(artifact.taskId()));
    }

    private RdAgentStageArtifactRow row(AgentStageArtifact artifact) {
        RdAgentStageArtifactRow row = new RdAgentStageArtifactRow();
        row.id = Long.parseLong(artifact.artifactId());
        row.stageRunId = Long.parseLong(artifact.stageRunId());
        row.taskId = Long.parseLong(artifact.taskId());
        row.role = artifact.role().name();
        row.artifactType = artifact.artifactType();
        row.artifactUri = artifact.artifactUri();
        row.summary = artifact.summary();
        row.contentPreview = artifact.contentPreview();
        row.contentHash = artifact.contentHash();
        row.metadataJson = artifact.metadataJson();
        row.createdAt = PostgresPersistenceSupport.toDateTime(artifact.createdAtEpochMillis());
        return row;
    }
}
