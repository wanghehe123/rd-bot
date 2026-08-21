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

import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PostgresAgentStageArtifactStore} with a mocked mapper.
 * Concurrent race coverage simulates {@code ON CONFLICT DO NOTHING} via an in-memory row map.
 * Real Postgres persistence is exercised indirectly by
 * {@link com.wish.rd.bootstrap.MultiAgentRequirementDeliveryRealSmokeTest} and
 * {@link com.wish.rd.bootstrap.ObservabilityMetricsRealSmokeTest} (rd_agent_stage_artifacts).
 */
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
        when(mapper.selectByTaskOmittingPrivateQaPreviews(7478000000000000000L)).thenReturn(List.of(row(artifact)));

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
    void listByTaskOmitsPrivateQaEvidenceContentPreviews() throws Exception {
        when(mapper.selectByTaskOmittingPrivateQaPreviews(7478000000000000000L)).thenReturn(List.of());
        store.listByTask("7478000000000000000");
        verify(mapper).selectByTaskOmittingPrivateQaPreviews(7478000000000000000L);
        verify(mapper, never()).selectList(any());

        Select select = RdAgentStageArtifactMapper.class
                .getMethod("selectByTaskOmittingPrivateQaPreviews", long.class)
                .getAnnotation(Select.class);
        String sql = String.join(" ", select.value());
        assertTrue(sql.contains("THEN NULL ELSE content_preview END"));
        assertTrue(sql.contains("'QA_SCREENSHOT'"));
        assertTrue(sql.contains("'QA_TRACE'"));
        assertTrue(sql.contains("'QA_COMMAND_LOG'"));
        assertTrue(sql.contains("'QA_CONSOLE_LOG'"));
        assertTrue(sql.contains("'QA_NETWORK_LOG'"));
        assertTrue(sql.contains("'QA_HTTP_TRANSCRIPT'"));
        assertTrue(sql.contains("'QA_VIDEO'"));
        assertTrue(sql.contains("'QA_EVIDENCE_MANIFEST'"));
        assertFalse(sql.contains("'AGENT_EVENTS'"));
        assertFalse(sql.contains("'PROMPT_SNAPSHOT'"));
    }

    @Test
    void listByTaskStageAndTypeDoesNotScanEveryTaskArtifact() {
        AgentStageArtifact artifact = new AgentStageArtifact(
                "7478000000000000201",
                "7478000000000000101",
                "7478000000000000000",
                AgentRole.CODING_AGENT,
                "AGENT_EVENTS",
                "rd-agent-stage://7478000000000000000/7478000000000000101/events",
                "Pi agent events",
                "{\"protocol\":\"rd-agent-event/v1\"}",
                "sha256:events",
                "{}",
                1_783_000_000_000L
        );
        when(mapper.selectList(any())).thenReturn(List.of(row(artifact)));

        assertEquals(List.of(artifact), store.listByTaskStageAndType(
                artifact.taskId(), artifact.stageRunId(), "AGENT_EVENTS"));

        verify(mapper).selectList(any());
        verify(mapper, never()).selectByTaskOmittingPrivateQaPreviews(anyLong());
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
        when(mapper.insertStageArtifactIgnoringConflict(any(RdAgentStageArtifactRow.class))).thenReturn(1, 0);
        when(mapper.selectById(7478000000000000202L)).thenReturn(row(artifact));

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

        verify(mapper, times(2)).insertStageArtifactIgnoringConflict(any(RdAgentStageArtifactRow.class));
        verify(mapper, never()).upsertStageArtifact(any(RdAgentStageArtifactRow.class));
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
        when(mapper.insertStageArtifactIgnoringConflict(any(RdAgentStageArtifactRow.class))).thenReturn(0);
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
        verify(mapper, never()).upsertStageArtifact(any(RdAgentStageArtifactRow.class));
    }

    @Test
    void saveImmutableConcurrentDifferentHashShouldRetainFirstWriterAndThrowSecond() throws Exception {
        long artifactId = 7478000000000000204L;
        AgentStageArtifact first = new AgentStageArtifact(
                String.valueOf(artifactId),
                "7478000000000000101",
                "7478000000000000000",
                AgentRole.CODING_AGENT,
                "ROLE_EXECUTION_INPUT_MANIFEST",
                "rd-agent-stage://7478000000000000000/7478000000000000101/manifest",
                "manifest",
                "{\"version\":1}",
                "sha256:first",
                "{}",
                1_783_000_000_000L
        );
        AgentStageArtifact second = new AgentStageArtifact(
                first.artifactId(),
                first.stageRunId(),
                first.taskId(),
                first.role(),
                first.artifactType(),
                first.artifactUri(),
                first.summary(),
                "{\"version\":2}",
                "sha256:second",
                first.metadataJson(),
                first.createdAtEpochMillis()
        );
        ConcurrentHashMap<Long, RdAgentStageArtifactRow> rows = new ConcurrentHashMap<>();
        AtomicReference<String> committedHash = new AtomicReference<>();
        when(mapper.insertStageArtifactIgnoringConflict(any(RdAgentStageArtifactRow.class))).thenAnswer(invocation -> {
            RdAgentStageArtifactRow row = invocation.getArgument(0);
            RdAgentStageArtifactRow existing = rows.putIfAbsent(row.id, row);
            if (existing == null) {
                committedHash.compareAndSet(null, row.contentHash);
                return 1;
            }
            return 0;
        });
        when(mapper.selectById(artifactId)).thenAnswer(invocation -> rows.get(artifactId));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<AgentStageArtifact> winner = new AtomicReference<>();
        AtomicReference<Throwable> loserError = new AtomicReference<>();
        AtomicReference<String> rejectedHash = new AtomicReference<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            executor.submit(() -> runConcurrentSave(
                    store, first, ready, start, winner, loserError, rejectedHash));
            executor.submit(() -> runConcurrentSave(
                    store, second, ready, start, winner, loserError, rejectedHash));
            ready.await();
            start.countDown();
            executor.shutdown();
            assertEquals(true, executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }

        assertNotNull(winner.get());
        assertNotNull(committedHash.get());
        assertEquals(committedHash.get(), winner.get().contentHash());
        assertNotNull(rows.get(artifactId));
        assertEquals(committedHash.get(), rows.get(artifactId).contentHash);
        assertNotNull(loserError.get());
        assertEquals(IllegalStateException.class, loserError.get().getClass());
        assertEquals("immutable artifact conflict: " + artifactId, loserError.get().getMessage());
        assertNotNull(rejectedHash.get());
        assertNotEquals(committedHash.get(), rejectedHash.get());
        verify(mapper, never()).upsertStageArtifact(any(RdAgentStageArtifactRow.class));
    }

    @Test
    void saveImmutableConcurrentSameHashShouldReturnStoredContentWithoutOverwrite() throws Exception {
        long artifactId = 7478000000000000205L;
        AgentStageArtifact first = new AgentStageArtifact(
                String.valueOf(artifactId),
                "7478000000000000101",
                "7478000000000000000",
                AgentRole.CODING_AGENT,
                "ROLE_EXECUTION_INPUT_MANIFEST",
                "rd-agent-stage://7478000000000000000/7478000000000000101/manifest",
                "manifest",
                "{\"version\":1}",
                "sha256:same",
                "{}",
                1_783_000_000_000L
        );
        AgentStageArtifact second = new AgentStageArtifact(
                first.artifactId(),
                first.stageRunId(),
                first.taskId(),
                first.role(),
                first.artifactType(),
                first.artifactUri(),
                first.summary(),
                "{\"version\":2}",
                first.contentHash(),
                first.metadataJson(),
                first.createdAtEpochMillis()
        );
        ConcurrentHashMap<Long, RdAgentStageArtifactRow> rows = new ConcurrentHashMap<>();
        when(mapper.insertStageArtifactIgnoringConflict(any(RdAgentStageArtifactRow.class))).thenAnswer(invocation -> {
            RdAgentStageArtifactRow row = invocation.getArgument(0);
            return rows.putIfAbsent(row.id, row) == null ? 1 : 0;
        });
        when(mapper.selectById(artifactId)).thenAnswer(invocation -> rows.get(artifactId));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<AgentStageArtifact> firstResult = new AtomicReference<>();
        AtomicReference<AgentStageArtifact> secondResult = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            executor.submit(() -> runConcurrentSaveSameHash(store, first, ready, start, firstResult, error));
            executor.submit(() -> runConcurrentSaveSameHash(store, second, ready, start, secondResult, error));
            ready.await();
            start.countDown();
            executor.shutdown();
            assertEquals(true, executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }

        assertEquals(null, error.get());
        assertNotNull(firstResult.get());
        assertNotNull(secondResult.get());
        assertEquals(firstResult.get().contentPreview(), secondResult.get().contentPreview());
        assertEquals("sha256:same", firstResult.get().contentHash());
        assertEquals(firstResult.get().contentHash(), secondResult.get().contentHash());
        verify(mapper, never()).upsertStageArtifact(any(RdAgentStageArtifactRow.class));
    }

    private static void runConcurrentSaveSameHash(
            PostgresAgentStageArtifactStore store,
            AgentStageArtifact artifact,
            CountDownLatch ready,
            CountDownLatch start,
            AtomicReference<AgentStageArtifact> result,
            AtomicReference<Throwable> error
    ) {
        ready.countDown();
        try {
            start.await();
            result.set(store.saveImmutable(artifact));
        } catch (Throwable throwable) {
            error.compareAndSet(null, throwable);
        }
    }

    private static void runConcurrentSave(
            PostgresAgentStageArtifactStore store,
            AgentStageArtifact artifact,
            CountDownLatch ready,
            CountDownLatch start,
            AtomicReference<AgentStageArtifact> winner,
            AtomicReference<Throwable> loserError,
            AtomicReference<String> rejectedHash
    ) {
        ready.countDown();
        try {
            start.await();
            AgentStageArtifact saved = store.saveImmutable(artifact);
            winner.compareAndSet(null, saved);
        } catch (Throwable throwable) {
            rejectedHash.compareAndSet(null, artifact.contentHash());
            loserError.compareAndSet(null, throwable);
        }
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
