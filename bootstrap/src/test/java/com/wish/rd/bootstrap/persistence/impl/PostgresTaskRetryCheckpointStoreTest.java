package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.TaskRetryCheckpointRow;
import com.wish.rd.bootstrap.persistence.mapper.TaskRetryCheckpointMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskRetryPoint;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresTaskRetryCheckpointStoreTest {

    @Test
    void createOrGetIsTransactionalAndIdempotentBySourceTaskVersion() throws Exception {
        Method method = PostgresTaskRetryCheckpointStore.class.getMethod(
                "createOrGet", TaskRetryCheckpoint.class);
        assertNotNull(method.getAnnotation(Transactional.class));

        Map<Long, TaskRetryCheckpointRow> rows = new LinkedHashMap<>();
        TaskRetryCheckpointMapper mapper = mock(TaskRetryCheckpointMapper.class);
        when(mapper.insertIfAbsent(any())).thenAnswer(invocation -> {
            TaskRetryCheckpointRow row = invocation.getArgument(0);
            boolean duplicate = rows.values().stream()
                    .anyMatch(existing -> existing.idempotencyKey.equals(row.idempotencyKey));
            if (duplicate) {
                return 0;
            }
            rows.put(row.id, copy(row));
            return 1;
        });
        when(mapper.selectByIdempotencyKey(any())).thenAnswer(invocation -> rows.values().stream()
                .filter(row -> row.idempotencyKey.equals(invocation.getArgument(0)))
                .findFirst().map(PostgresTaskRetryCheckpointStoreTest::copy).orElse(null));

        PostgresTaskRetryCheckpointStore store = new PostgresTaskRetryCheckpointStore(mapper);
        TaskRetryCheckpoint first = checkpoint("8001", "9001", "9001:12:AGENT_ROLE:CODING_AGENT");
        TaskRetryCheckpoint duplicate = checkpoint("8002", "9001", first.idempotencyKey());

        var created = store.createOrGet(first);
        var reused = store.createOrGet(duplicate);

        assertEquals(true, created.created());
        assertEquals(false, reused.created());
        assertEquals("8001", reused.checkpoint().checkpointId());
        assertEquals(1, rows.size());
    }

    @Test
    void idempotencyReplayRejectsDifferentImmutableRouteInput() {
        TaskRetryCheckpointMapper mapper = mock(TaskRetryCheckpointMapper.class);
        TaskRetryCheckpoint first = checkpoint("8001", "9001", "retry-key");
        TaskRetryCheckpointRow stored = row(first);
        when(mapper.insertIfAbsent(any())).thenReturn(0);
        when(mapper.selectByIdempotencyKey("retry-key")).thenReturn(stored);
        PostgresTaskRetryCheckpointStore store = new PostgresTaskRetryCheckpointStore(mapper);
        TaskRetryCheckpoint conflicting = new TaskRetryCheckpoint(
                "8002", "9001", TaskFailurePhase.AGENT_ROLE, AgentRole.CODING_AGENT,
                "7001", "", "", 1, "retry-key", RdTaskStatus.FAILED_NEEDS_HUMAN, 12L,
                "different operator note", List.of("7001", "7002"), TaskRetryCheckpointStatus.CREATED,
                "coding failed", "", 100L, 100L);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> store.createOrGet(conflicting));
    }

    @Test
    void transitionUsesExpectedStatusCompareAndSet() {
        Map<Long, TaskRetryCheckpointRow> rows = new LinkedHashMap<>();
        TaskRetryCheckpointMapper mapper = mock(TaskRetryCheckpointMapper.class);
        TaskRetryCheckpointRow initial = row(checkpoint("8001", "9001", "retry-key"));
        rows.put(initial.id, initial);
        when(mapper.selectById(any())).thenAnswer(invocation -> copy(rows.get(((Number) invocation.getArgument(0)).longValue())));
        when(mapper.transition(any())).thenAnswer(invocation -> {
            TaskRetryCheckpointRow update = invocation.getArgument(0);
            TaskRetryCheckpointRow current = rows.get(update.id);
            if (current == null || !current.status.equals(update.expectedStatus)) {
                return 0;
            }
            current.status = update.status;
            current.errorMessage = update.errorMessage;
            current.updatedAt = update.updatedAt;
            return 1;
        });

        PostgresTaskRetryCheckpointStore store = new PostgresTaskRetryCheckpointStore(mapper);
        TaskRetryCheckpoint dispatched = store.transition(
                "8001", TaskRetryCheckpointStatus.CREATED, TaskRetryCheckpointStatus.DISPATCHED, "", 200L);

        assertEquals(TaskRetryCheckpointStatus.DISPATCHED, dispatched.status());
    }

    @Test
    void dispatchedCheckpointCanEnterAndLeaveStableApprovalWait() {
        Map<Long, TaskRetryCheckpointRow> rows = new LinkedHashMap<>();
        TaskRetryCheckpointMapper mapper = mock(TaskRetryCheckpointMapper.class);
        TaskRetryCheckpointRow initial = row(checkpoint("8001", "9001", "retry-key"));
        initial.status = TaskRetryCheckpointStatus.DISPATCHED.name();
        rows.put(initial.id, initial);
        when(mapper.selectById(any())).thenAnswer(invocation -> copy(rows.get(((Number) invocation.getArgument(0)).longValue())));
        when(mapper.transition(any())).thenAnswer(invocation -> {
            TaskRetryCheckpointRow update = invocation.getArgument(0);
            TaskRetryCheckpointRow current = rows.get(update.id);
            if (!current.status.equals(update.expectedStatus)) return 0;
            current.status = update.status; current.updatedAt = update.updatedAt; return 1;
        });
        PostgresTaskRetryCheckpointStore store = new PostgresTaskRetryCheckpointStore(mapper);

        TaskRetryCheckpoint waiting = store.transition("8001", TaskRetryCheckpointStatus.DISPATCHED,
                TaskRetryCheckpointStatus.WAITING_APPROVAL, "", 200L);
        TaskRetryCheckpoint resumed = store.transition("8001", TaskRetryCheckpointStatus.WAITING_APPROVAL,
                TaskRetryCheckpointStatus.DISPATCHED, "", 300L);

        assertEquals(TaskRetryCheckpointStatus.WAITING_APPROVAL, waiting.status());
        assertEquals(TaskRetryCheckpointStatus.DISPATCHED, resumed.status());
    }

    @Test
    void roundTripsImmutableOperatorNoteAndEvidenceMaterialIds() {
        Map<Long, TaskRetryCheckpointRow> rows = new LinkedHashMap<>();
        TaskRetryCheckpointMapper mapper = mock(TaskRetryCheckpointMapper.class);
        when(mapper.insertIfAbsent(any())).thenAnswer(invocation -> {
            TaskRetryCheckpointRow inserted = invocation.getArgument(0);
            rows.put(inserted.id, copy(inserted));
            return 1;
        });
        when(mapper.selectById(any())).thenAnswer(invocation -> copy(rows.get(
                ((Number) invocation.getArgument(0)).longValue())));
        PostgresTaskRetryCheckpointStore store = new PostgresTaskRetryCheckpointStore(mapper);
        TaskRetryCheckpoint checkpoint = checkpoint("8001", "9001", "retry-key");

        store.createOrGet(checkpoint);
        TaskRetryCheckpoint reloaded = store.find("8001").orElseThrow();

        assertEquals("账号使用 user1", reloaded.operatorNote());
        assertEquals(List.of("7001", "7002"), reloaded.evidenceMaterialIds());
    }

    @Test
    void roundTripsVerifiedCheckpointFenceGenerationAndFailureCommand() {
        Map<Long, TaskRetryCheckpointRow> rows = new LinkedHashMap<>();
        TaskRetryCheckpointMapper mapper = mock(TaskRetryCheckpointMapper.class);
        when(mapper.insertIfAbsent(any())).thenAnswer(invocation -> {
            TaskRetryCheckpointRow inserted = invocation.getArgument(0); rows.put(inserted.id, copy(inserted)); return 1;
        });
        when(mapper.selectById(any())).thenAnswer(invocation -> copy(rows.get(((Number) invocation.getArgument(0)).longValue())));
        TaskRetryCheckpoint checkpoint = TaskRetryCheckpoint.created("8001", new TaskRetryPoint(
                "9001", TaskFailurePhase.AGENT_ROLE, AgentRole.CODING_AGENT, "7001", "", "", "failed",
                12L, 33L, "701", "ROLE_EXECUTION:CODING_AGENT", "301", "sha256:abc", ""),
                1, "verified-key", RdTaskStatus.FAILED_NEEDS_HUMAN, 100L);

        PostgresTaskRetryCheckpointStore store = new PostgresTaskRetryCheckpointStore(mapper);
        store.createOrGet(checkpoint);
        TaskRetryCheckpoint reloaded = store.find("8001").orElseThrow();

        assertEquals(33L, reloaded.sourceFencingToken());
        assertEquals("701", reloaded.failedStageCommandId());
        assertEquals(8001L, reloaded.businessGeneration());
    }

    private static TaskRetryCheckpoint checkpoint(String id, String taskId, String key) {
        return new TaskRetryCheckpoint(
                id, taskId, TaskFailurePhase.AGENT_ROLE, AgentRole.CODING_AGENT,
                "7001", "", "", 1, key, RdTaskStatus.FAILED_NEEDS_HUMAN, 12L,
                "账号使用 user1", List.of("7001", "7002"),
                TaskRetryCheckpointStatus.CREATED, "coding failed", "", 100L, 100L);
    }

    private static TaskRetryCheckpointRow row(TaskRetryCheckpoint checkpoint) {
        TaskRetryCheckpointRow row = new TaskRetryCheckpointRow();
        row.id = Long.parseLong(checkpoint.checkpointId());
        row.taskId = Long.parseLong(checkpoint.taskId());
        row.failurePhase = checkpoint.failurePhase().name();
        row.retryFromRole = checkpoint.retryFromRole().name();
        row.failedStageRunId = Long.parseLong(checkpoint.failedStageRunId());
        row.attemptNo = checkpoint.attemptNo();
        row.idempotencyKey = checkpoint.idempotencyKey();
        row.sourceTaskStatus = checkpoint.sourceTaskStatus().name();
        row.sourceTaskVersion = checkpoint.sourceTaskVersion();
        row.sourceFencingToken = checkpoint.sourceFencingToken();
        row.failedStageCommandId = checkpoint.failedStageCommandId().isBlank() ? null : Long.valueOf(checkpoint.failedStageCommandId());
        row.failedStage = checkpoint.failedStage();
        row.sourcePolicyRunId = checkpoint.sourcePolicyRunId().isBlank() ? null : Long.valueOf(checkpoint.sourcePolicyRunId());
        row.policyRunId = checkpoint.policyRunId().isBlank() ? null : Long.valueOf(checkpoint.policyRunId());
        row.sourcePlanDigest = checkpoint.sourcePlanDigest();
        row.authorizationPlanDigest = checkpoint.authorizationPlanDigest();
        row.publicationOperationId = checkpoint.publicationOperationId();
        row.dispatchTaskVersion = checkpoint.dispatchTaskVersion();
        row.dispatchFencingToken = checkpoint.dispatchFencingToken();
        row.dispatchCommandId = checkpoint.dispatchCommandId().isBlank() ? null : Long.valueOf(checkpoint.dispatchCommandId());
        row.businessGeneration = checkpoint.businessGeneration();
        row.operatorNote = checkpoint.operatorNote();
        row.evidenceMaterialIdsJson = "[\"7001\",\"7002\"]";
        row.status = checkpoint.status().name();
        row.reason = checkpoint.reason();
        row.errorMessage = checkpoint.errorMessage();
        row.createdAt = OffsetDateTime.now();
        row.updatedAt = row.createdAt;
        return row;
    }

    private static TaskRetryCheckpointRow copy(TaskRetryCheckpointRow source) {
        if (source == null) {
            return null;
        }
        TaskRetryCheckpointRow copy = new TaskRetryCheckpointRow();
        copy.id = source.id;
        copy.taskId = source.taskId;
        copy.failurePhase = source.failurePhase;
        copy.retryFromRole = source.retryFromRole;
        copy.failedStageRunId = source.failedStageRunId;
        copy.failedRetrievalRunId = source.failedRetrievalRunId;
        copy.failedAiReviewRunId = source.failedAiReviewRunId;
        copy.attemptNo = source.attemptNo;
        copy.idempotencyKey = source.idempotencyKey;
        copy.sourceTaskStatus = source.sourceTaskStatus;
        copy.sourceTaskVersion = source.sourceTaskVersion;
        copy.sourceFencingToken = source.sourceFencingToken;
        copy.failedStageCommandId = source.failedStageCommandId;
        copy.failedStage = source.failedStage;
        copy.sourcePolicyRunId = source.sourcePolicyRunId;
        copy.policyRunId = source.policyRunId;
        copy.sourcePlanDigest = source.sourcePlanDigest;
        copy.authorizationPlanDigest = source.authorizationPlanDigest;
        copy.publicationOperationId = source.publicationOperationId;
        copy.dispatchTaskVersion = source.dispatchTaskVersion;
        copy.dispatchFencingToken = source.dispatchFencingToken;
        copy.dispatchCommandId = source.dispatchCommandId;
        copy.businessGeneration = source.businessGeneration;
        copy.operatorNote = source.operatorNote;
        copy.evidenceMaterialIdsJson = source.evidenceMaterialIdsJson;
        copy.status = source.status;
        copy.reason = source.reason;
        copy.errorMessage = source.errorMessage;
        copy.createdAt = source.createdAt;
        copy.updatedAt = source.updatedAt;
        copy.expectedStatus = source.expectedStatus;
        return copy;
    }
}
