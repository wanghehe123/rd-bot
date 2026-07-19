package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.RdRagRetrievalArtifactRow;
import com.wish.rd.bootstrap.persistence.entity.RdRagRetrievalEventRow;
import com.wish.rd.bootstrap.persistence.entity.RdRagRetrievalRunRow;
import com.wish.rd.bootstrap.persistence.mapper.RdRagRetrievalArtifactMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdRagRetrievalEventMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdRagRetrievalRunMapper;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.retrieval.run.model.EvidenceQualityDecision;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresRetrievalRunStoreTest {

    @Test
    void createAndRetryAreTransactional() throws Exception {
        Method create = PostgresRetrievalRunStore.class.getMethod("create", RetrievalRun.class);
        Method retry = PostgresRetrievalRunStore.class.getMethod(
                "retry", String.class, String.class, long.class
        );

        assertNotNull(create.getAnnotation(Transactional.class));
        assertNotNull(retry.getAnnotation(Transactional.class));
    }

    @Test
    void retryReturnsExistingChildWhenParentAlreadyRetried() {
        Map<Long, RdRagRetrievalRunRow> rows = new LinkedHashMap<>();
        RdRagRetrievalRunMapper runMapper = mock(RdRagRetrievalRunMapper.class);
        RdRagRetrievalEventMapper eventMapper = mock(RdRagRetrievalEventMapper.class);
        RdRagRetrievalArtifactMapper artifactMapper = mock(RdRagRetrievalArtifactMapper.class);

        doAnswer(invocation -> {
            RdRagRetrievalRunRow row = invocation.getArgument(0);
            rows.put(row.id, copy(row));
            return null;
        }).when(runMapper).insertRun(any());
        when(runMapper.selectById(any())).thenAnswer(invocation ->
                copy(rows.get(((Number) invocation.getArgument(0)).longValue())));
        when(runMapper.selectList(any())).thenAnswer(invocation ->
                rows.values().stream().map(PostgresRetrievalRunStoreTest::copy).toList());
        when(runMapper.transition(any())).thenAnswer(invocation -> {
            RdRagRetrievalRunRow row = invocation.getArgument(0);
            RdRagRetrievalRunRow current = rows.get(row.id);
            if (current == null || !java.util.Objects.equals(current.version, row.expectedVersion)) {
                return 0;
            }
            rows.put(row.id, copy(row));
            return 1;
        });
        doAnswer(invocation -> null).when(eventMapper).insertEvent(any(RdRagRetrievalEventRow.class));
        doAnswer(invocation -> null).when(artifactMapper).insertArtifact(any(RdRagRetrievalArtifactRow.class));

        PostgresRetrievalRunStore store = new PostgresRetrievalRunStore(
                runMapper, eventMapper, artifactMapper,
                SnowflakeIdGenerator.defaultGenerator(), new ObjectMapper()
        );

        RetrievalRun parent = store.create(run("9001", "8001", 1, ""));
        RetrievalRun terminal = store.transition(
                parent.runId(), parent.version(), RetrievalRunStatus.PLANNING, 0,
                null, "", "", "", "system", 101L
        );
        terminal = store.transition(
                terminal.runId(), terminal.version(), RetrievalRunStatus.FAILED_RETRYABLE, 0,
                null, "provider unavailable", "PROVIDER", "timeout", "system", 102L
        );

        RetrievalRun firstChild = store.retry(terminal.runId(), "9002", 103L);
        RetrievalRun secondChild = store.retry(terminal.runId(), "9003", 104L);

        assertEquals(firstChild.runId(), secondChild.runId());
        assertEquals("9002", firstChild.runId());
        assertEquals(2, rows.size());
    }

    private static RetrievalRun run(String runId, String taskId, int attemptNo, String parentRunId) {
        return new RetrievalRun(
                runId, taskId, RetrievalConsumerType.BUG_FIX, "", "", attemptNo, parentRunId,
                taskId + ":BUG_FIX:" + attemptNo, RetrievalRunStatus.CREATED, List.of("1"),
                "sha256:query", "redacted query", 0, 3, 18_000, 0, 0,
                EvidenceQualityDecision.SUFFICIENT, "", "", "", "", 0L, 0L, 100L, 100L
        );
    }

    private static RdRagRetrievalRunRow copy(RdRagRetrievalRunRow source) {
        if (source == null) {
            return null;
        }
        RdRagRetrievalRunRow copy = new RdRagRetrievalRunRow();
        copy.id = source.id;
        copy.taskId = source.taskId;
        copy.consumerType = source.consumerType;
        copy.role = source.role;
        copy.stageRunId = source.stageRunId;
        copy.attemptNo = source.attemptNo;
        copy.parentRunId = source.parentRunId;
        copy.idempotencyKey = source.idempotencyKey;
        copy.status = source.status;
        copy.knowledgeBaseIdsJson = source.knowledgeBaseIdsJson;
        copy.queryHash = source.queryHash;
        copy.queryPreview = source.queryPreview;
        copy.currentIteration = source.currentIteration;
        copy.maxIterations = source.maxIterations;
        copy.contextBudgetChars = source.contextBudgetChars;
        copy.candidateCount = source.candidateCount;
        copy.selectedEvidenceCount = source.selectedEvidenceCount;
        copy.qualityDecision = source.qualityDecision;
        copy.stopReason = source.stopReason;
        copy.errorCategory = source.errorCategory;
        copy.errorMessage = source.errorMessage;
        copy.leaseOwner = source.leaseOwner;
        copy.leaseUntil = source.leaseUntil;
        copy.version = source.version;
        copy.createdAt = source.createdAt;
        copy.updatedAt = source.updatedAt;
        copy.expectedVersion = source.expectedVersion;
        return copy;
    }
}
