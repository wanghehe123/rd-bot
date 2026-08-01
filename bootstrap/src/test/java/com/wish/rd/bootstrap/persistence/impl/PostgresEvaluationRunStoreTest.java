package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.EvaluationEventRow;
import com.wish.rd.bootstrap.persistence.entity.EvaluationRunRow;
import com.wish.rd.bootstrap.persistence.mapper.EvaluationArtifactMapper;
import com.wish.rd.bootstrap.persistence.mapper.EvaluationEventMapper;
import com.wish.rd.bootstrap.persistence.mapper.EvaluationRunMapper;
import com.wish.rd.engine.evaluation.model.EvaluationExecutionResult;
import com.wish.rd.engine.evaluation.model.EvaluationJudgeProvider;
import com.wish.rd.engine.evaluation.model.EvaluationRun;
import com.wish.rd.engine.evaluation.model.EvaluationRunConfig;
import com.wish.rd.engine.evaluation.model.EvaluationRunQuery;
import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;
import com.wish.rd.engine.evaluation.model.EvaluationSource;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresEvaluationRunStoreTest {

    @Test
    void shouldRunHistoryPaginationInPostgresInsteadOfLoadingEveryRun() {
        EvaluationRunMapper runMapper = mock(EvaluationRunMapper.class);
        EvaluationEventMapper eventMapper = mock(EvaluationEventMapper.class);
        EvaluationArtifactMapper artifactMapper = mock(EvaluationArtifactMapper.class);
        PostgresEvaluationRunStore store = store(runMapper, eventMapper, artifactMapper);

        store.query(new EvaluationRunQuery(
                "", null, List.of(), false, null, "", "", List.of(), 1, 10));

        verify(runMapper, never()).selectList(any());
        verify(runMapper).selectHistoryPage(any(), eq(10), eq(0L));
        verify(runMapper).countHistoryPage(any());
        verify(runMapper).selectHistoryOverview(any());
    }

    @Test
    void shouldInsertCreatedRunAndAppendCreatedEvent() {
        EvaluationRunMapper runMapper = mock(EvaluationRunMapper.class);
        EvaluationEventMapper eventMapper = mock(EvaluationEventMapper.class);
        EvaluationArtifactMapper artifactMapper = mock(EvaluationArtifactMapper.class);
        PostgresEvaluationRunStore store = store(runMapper, eventMapper, artifactMapper);
        EvaluationRun run = EvaluationRun.created("101", config(), 1, "", 1_000L);

        store.create(run);

        ArgumentCaptor<EvaluationRunRow> runRow = ArgumentCaptor.forClass(EvaluationRunRow.class);
        verify(runMapper).insertRun(runRow.capture());
        assertEquals("CREATED", runRow.getValue().status);
        assertEquals("fixture-web", runRow.getValue().name);
        assertEquals(false, runRow.getValue().dispatchPaused);
        ArgumentCaptor<EvaluationEventRow> eventRow = ArgumentCaptor.forClass(EvaluationEventRow.class);
        verify(eventMapper).insertEvent(eventRow.capture());
        assertEquals("CREATED", eventRow.getValue().toStatus);
        assertEquals("", eventRow.getValue().fromStatus);
    }

    @Test
    void shouldAtomicallyTransitionSnapshotAndEvent() throws Exception {
        EvaluationRunMapper runMapper = mock(EvaluationRunMapper.class);
        EvaluationEventMapper eventMapper = mock(EvaluationEventMapper.class);
        EvaluationArtifactMapper artifactMapper = mock(EvaluationArtifactMapper.class);
        PostgresEvaluationRunStore store = store(runMapper, eventMapper, artifactMapper);
        when(runMapper.selectById(101L)).thenReturn(row(EvaluationRunStatus.QUEUED, 1L));
        when(runMapper.compareAndSet(any())).thenReturn(1);

        EvaluationRun updated = store.transition("101", EvaluationRunStatus.QUEUED,
                EvaluationRunStatus.RECORDING, "record", "", "", 2_000L);

        assertEquals(EvaluationRunStatus.RECORDING, updated.status());
        ArgumentCaptor<EvaluationRunRow> rowCaptor = ArgumentCaptor.forClass(EvaluationRunRow.class);
        verify(runMapper).compareAndSet(rowCaptor.capture());
        assertEquals("QUEUED", rowCaptor.getValue().expectedStatus);
        assertEquals(1L, rowCaptor.getValue().expectedVersion);
        ArgumentCaptor<EvaluationEventRow> eventCaptor = ArgumentCaptor.forClass(EvaluationEventRow.class);
        verify(eventMapper).insertEvent(eventCaptor.capture());
        assertEquals("QUEUED", eventCaptor.getValue().fromStatus);
        assertEquals("RECORDING", eventCaptor.getValue().toStatus);
    }

    @Test
    void shouldRoundTripDispatchPausedBetweenRowAndSnapshot() throws Exception {
        EvaluationRunMapper runMapper = mock(EvaluationRunMapper.class);
        EvaluationEventMapper eventMapper = mock(EvaluationEventMapper.class);
        EvaluationArtifactMapper artifactMapper = mock(EvaluationArtifactMapper.class);
        PostgresEvaluationRunStore store = store(runMapper, eventMapper, artifactMapper);
        EvaluationRunRow paused = row(EvaluationRunStatus.QUEUED, 1L);
        paused.dispatchPaused = true;
        when(runMapper.selectById(101L)).thenReturn(paused);
        when(runMapper.compareAndSet(any())).thenReturn(1);

        EvaluationRun loaded = store.find("101").orElseThrow();
        EvaluationRun updated = store.transition("101", EvaluationRunStatus.QUEUED,
                EvaluationRunStatus.RECORDING, "record", "", "", 2_000L);

        assertEquals(true, loaded.dispatchPaused());
        assertEquals(true, updated.dispatchPaused());
        ArgumentCaptor<EvaluationRunRow> rowCaptor = ArgumentCaptor.forClass(EvaluationRunRow.class);
        verify(runMapper).compareAndSet(rowCaptor.capture());
        assertEquals(true, rowCaptor.getValue().dispatchPaused);
    }

    @Test
    void shouldRejectTerminalMutationBeforeIssuingUpdate() throws Exception {
        EvaluationRunMapper runMapper = mock(EvaluationRunMapper.class);
        EvaluationEventMapper eventMapper = mock(EvaluationEventMapper.class);
        EvaluationArtifactMapper artifactMapper = mock(EvaluationArtifactMapper.class);
        PostgresEvaluationRunStore store = store(runMapper, eventMapper, artifactMapper);
        when(runMapper.selectById(101L)).thenReturn(row(EvaluationRunStatus.SUCCEEDED, 7L));

        assertThrows(IllegalStateException.class, () -> store.transition("101",
                EvaluationRunStatus.SUCCEEDED, EvaluationRunStatus.QUEUED, "retry", "", "", 3_000L));

        verify(runMapper, never()).compareAndSet(any());
        verify(eventMapper, never()).insertEvent(any());
    }

    @Test
    void shouldPersistParsedScoreSummaryOnCompletion() throws Exception {
        EvaluationRunMapper runMapper = mock(EvaluationRunMapper.class);
        EvaluationEventMapper eventMapper = mock(EvaluationEventMapper.class);
        EvaluationArtifactMapper artifactMapper = mock(EvaluationArtifactMapper.class);
        PostgresEvaluationRunStore store = store(runMapper, eventMapper, artifactMapper);
        when(runMapper.selectById(101L)).thenReturn(row(EvaluationRunStatus.REPORTING, 4L));
        when(runMapper.compareAndSet(any())).thenReturn(1);
        EvaluationExecutionResult result = new EvaluationExecutionResult(
                6, 5, 1, false, "[{\"name\":\"evidence_hit@5\",\"value\":0.9}]", List.of());

        EvaluationRun completed = store.complete("101", EvaluationRunStatus.REPORTING, result, 4_000L);

        assertEquals(EvaluationRunStatus.SUCCEEDED, completed.status());
        assertEquals(6, completed.sampleCount());
        assertEquals(1, completed.failedSampleCount());
        ArgumentCaptor<EvaluationRunRow> captor = ArgumentCaptor.forClass(EvaluationRunRow.class);
        verify(runMapper).compareAndSet(captor.capture());
        assertEquals("SUCCEEDED", captor.getValue().status);
        assertEquals(6, captor.getValue().sampleCount);
        assertEquals(false, captor.getValue().overallPassed);
    }

    private static PostgresEvaluationRunStore store(
            EvaluationRunMapper runMapper,
            EvaluationEventMapper eventMapper,
            EvaluationArtifactMapper artifactMapper
    ) {
        SnowflakeIdGenerator generator = mock(SnowflakeIdGenerator.class);
        when(generator.nextIdString()).thenReturn("9001", "9002", "9003", "9004");
        return new PostgresEvaluationRunStore(runMapper, eventMapper, artifactMapper, new ObjectMapper(), generator);
    }

    private static EvaluationRunRow row(EvaluationRunStatus status, long version) throws Exception {
        EvaluationRunRow row = new EvaluationRunRow();
        row.id = 101L;
        row.name = "fixture-web";
        row.attemptNo = 1;
        row.status = status.name();
        row.phaseMessage = status.name().toLowerCase();
        row.progressPercent = status == EvaluationRunStatus.SUCCEEDED ? 100 : 5;
        row.configJson = new ObjectMapper().writeValueAsString(config());
        row.sampleCount = 0;
        row.passedSampleCount = 0;
        row.failedSampleCount = 0;
        row.overallPassed = false;
        row.metricsJson = "[]";
        row.errorCategory = "";
        row.errorMessage = "";
        row.version = version;
        row.createdAt = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(1_000L), ZoneOffset.UTC);
        row.updatedAt = row.createdAt;
        return row;
    }

    private static EvaluationRunConfig config() {
        return new EvaluationRunConfig(
                "fixture-web", "rd_eval_smoke.jsonl", EvaluationSource.FIXTURE, "local", 0,
                "http://127.0.0.1:18080", "rag-retrieval.jsonl", 30,
                EvaluationJudgeProvider.NONE, 0, false, "");
    }
}
