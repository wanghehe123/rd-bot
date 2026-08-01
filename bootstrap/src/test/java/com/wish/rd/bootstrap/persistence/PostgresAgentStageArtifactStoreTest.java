package com.wish.rd.bootstrap.persistence;

import com.wish.rd.bootstrap.persistence.impl.PostgresAgentStageArtifactStore;

import com.wish.rd.bootstrap.persistence.entity.RdAgentStageArtifactRow;
import com.wish.rd.bootstrap.persistence.entity.RdQaEvidenceObjectRow;
import com.wish.rd.bootstrap.persistence.mapper.RdAgentStageArtifactMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdQaEvidenceObjectMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @Test
    void shouldIndexPrivateQaEvidenceForTaskScopedDownloads() {
        RdQaEvidenceObjectMapper evidenceMapper = mock(RdQaEvidenceObjectMapper.class);
        PostgresAgentStageArtifactStore evidenceAwareStore = new PostgresAgentStageArtifactStore(
                mapper, evidenceMapper);
        AgentStageArtifact artifact = new AgentStageArtifact(
                "7478000000000000301",
                "7478000000000000102",
                "7478000000000000000",
                AgentRole.QA_AGENT,
                "QA_SCREENSHOT",
                "s3://rd-qa-evidence/abc.png",
                "current requirement screenshot",
                "",
                "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "{\"artifactName\":\"qa-evidence/screenshots/current.png\",\"contentType\":\"image/png\",\"bytes\":\"2048\",\"sha256\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"}",
                1_783_000_000_000L
        );

        evidenceAwareStore.save(artifact);

        ArgumentCaptor<RdQaEvidenceObjectRow> rowCaptor = ArgumentCaptor.forClass(RdQaEvidenceObjectRow.class);
        verify(evidenceMapper).upsertEvidenceObject(rowCaptor.capture());
        RdQaEvidenceObjectRow saved = rowCaptor.getValue();
        assertEquals(7478000000000000301L, saved.artifactId);
        assertEquals("qa-evidence/screenshots/current.png", saved.artifactName);
        assertEquals("s3://rd-qa-evidence/abc.png", saved.objectUri);
        assertEquals("image/png", saved.contentType);
        assertEquals(2048L, saved.sizeBytes);
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", saved.sha256);
    }

    @Test
    void saveImmutableShouldInsertOnceAndReturnExistingOnMatchingHash() {
        AgentStageArtifact artifact = new AgentStageArtifact(
                "7478000000000000202",
                "7478000000000000101",
                "7478000000000000000",
                AgentRole.CODING_AGENT,
                "ROLE_EXECUTION_INPUT_MANIFEST",
                "rd-agent-stage://7478000000000000000/7478000000000000101/manifest",
                "manifest",
                "{\"version\":1}",
                "sha256:abc",
                "{}",
                1_783_000_000_000L
        );
        when(mapper.selectById(7478000000000000202L)).thenReturn(null, row(artifact));

        store.saveImmutable(artifact);
        AgentStageArtifact again = store.saveImmutable(new AgentStageArtifact(
                artifact.artifactId(),
                artifact.stageRunId(),
                artifact.taskId(),
                artifact.role(),
                artifact.artifactType(),
                artifact.artifactUri(),
                artifact.summary(),
                "{\"version\":2}",
                artifact.contentHash(),
                artifact.metadataJson(),
                artifact.createdAtEpochMillis()
        ));

        verify(mapper).upsertStageArtifact(any(RdAgentStageArtifactRow.class));
        verify(mapper, never()).insert(org.mockito.ArgumentMatchers.<RdAgentStageArtifactRow>any());
        assertEquals(artifact.artifactId(), again.artifactId());
        assertEquals("{\"version\":1}", again.contentPreview());
    }

    @Test
    void saveImmutableShouldRejectConflictingHash() {
        AgentStageArtifact artifact = new AgentStageArtifact(
                "7478000000000000203",
                "7478000000000000101",
                "7478000000000000000",
                AgentRole.CODING_AGENT,
                "ROLE_EXECUTION_INPUT_MANIFEST",
                "rd-agent-stage://7478000000000000000/7478000000000000101/manifest",
                "manifest",
                "{\"version\":1}",
                "sha256:abc",
                "{}",
                1_783_000_000_000L
        );
        when(mapper.selectById(7478000000000000203L)).thenReturn(row(artifact));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> store.saveImmutable(
                new AgentStageArtifact(
                        artifact.artifactId(),
                        artifact.stageRunId(),
                        artifact.taskId(),
                        artifact.role(),
                        artifact.artifactType(),
                        artifact.artifactUri(),
                        artifact.summary(),
                        artifact.contentPreview(),
                        "sha256:def",
                        artifact.metadataJson(),
                        artifact.createdAtEpochMillis()
                )
        ));
        assertEquals("immutable artifact conflict: 7478000000000000203", error.getMessage());
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
