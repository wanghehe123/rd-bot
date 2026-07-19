package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.AiReviewArtifactRow;
import com.wish.rd.bootstrap.persistence.entity.AiReviewEventRow;
import com.wish.rd.bootstrap.persistence.entity.AiReviewRunRow;
import com.wish.rd.bootstrap.persistence.mapper.AiReviewArtifactMapper;
import com.wish.rd.bootstrap.persistence.mapper.AiReviewEventMapper;
import com.wish.rd.bootstrap.persistence.mapper.AiReviewRunMapper;
import com.wish.rd.engine.requirement.review.model.AiReviewDecision;
import com.wish.rd.engine.requirement.review.model.AiReviewResult;
import com.wish.rd.engine.requirement.review.model.AiReviewRun;
import com.wish.rd.engine.requirement.review.model.AiReviewRunStatus;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
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

class PostgresAiReviewRunStoreTest {

    @Test
    void transitionAndCompletionAreTransactionalWithAppendOnlyEvents() throws Exception {
        Method transition = PostgresAiReviewRunStore.class.getMethod(
                "transition", String.class, AiReviewRunStatus.class, AiReviewRunStatus.class,
                String.class, String.class, String.class, String.class, long.class);
        Method complete = PostgresAiReviewRunStore.class.getMethod(
                "complete", String.class, AiReviewRunStatus.class, AiReviewRunStatus.class,
                AiReviewResult.class, String.class, String.class, long.class);
        assertNotNull(transition.getAnnotation(Transactional.class));
        assertNotNull(complete.getAnnotation(Transactional.class));

        Map<Long, AiReviewRunRow> rows = new LinkedHashMap<>();
        List<AiReviewEventRow> events = new java.util.ArrayList<>();
        AiReviewRunMapper runMapper = mock(AiReviewRunMapper.class);
        AiReviewEventMapper eventMapper = mock(AiReviewEventMapper.class);
        AiReviewArtifactMapper artifactMapper = mock(AiReviewArtifactMapper.class);
        doAnswer(invocation -> {
            AiReviewRunRow row = invocation.getArgument(0);
            rows.put(row.id, copy(row));
            return null;
        }).when(runMapper).insertRun(any());
        when(runMapper.selectById(any())).thenAnswer(invocation -> copy(rows.get(((Number) invocation.getArgument(0)).longValue())));
        when(runMapper.compareAndSet(any())).thenAnswer(invocation -> {
            AiReviewRunRow update = invocation.getArgument(0);
            AiReviewRunRow current = rows.get(update.id);
            if (current == null || !current.status.equals(update.expectedStatus)
                    || !current.version.equals(update.expectedVersion)) {
                return 0;
            }
            rows.put(update.id, copy(update));
            return 1;
        });
        doAnswer(invocation -> {
            events.add(invocation.getArgument(0));
            return null;
        }).when(eventMapper).insertEvent(any());

        PostgresAiReviewRunStore store = new PostgresAiReviewRunStore(
                runMapper, eventMapper, artifactMapper, SnowflakeIdGenerator.defaultGenerator());
        AiReviewRun created = store.create(AiReviewRun.created("8001", "9001", 1, "", "model-a", 100L));
        AiReviewRun packaging = store.transition(created.runId(), AiReviewRunStatus.CREATED,
                AiReviewRunStatus.PACKAGING, "AUTO", "package", "", "", 110L);
        AiReviewRun reviewing = store.transition(packaging.runId(), AiReviewRunStatus.PACKAGING,
                AiReviewRunStatus.REVIEWING, "AUTO", "review", "", "", 120L);
        AiReviewRun validating = store.transition(reviewing.runId(), AiReviewRunStatus.REVIEWING,
                AiReviewRunStatus.VALIDATING, "AUTO", "validate", "", "", 130L);
        AiReviewRun terminal = store.complete(validating.runId(), AiReviewRunStatus.VALIDATING,
                AiReviewRunStatus.SUCCEEDED_OK,
                new AiReviewResult(AiReviewDecision.OK, 95, "accepted", null, List.of(), List.of(), "{}"),
                "AUTO", "accepted", 140L);

        assertEquals(AiReviewRunStatus.SUCCEEDED_OK, terminal.status());
        assertEquals(4, events.size());
    }

    @Test
    void retryCreatesParentLinkedChildWithoutMutatingTerminalRun() {
        AiReviewRunMapper runMapper = mock(AiReviewRunMapper.class);
        AiReviewEventMapper eventMapper = mock(AiReviewEventMapper.class);
        AiReviewArtifactMapper artifactMapper = mock(AiReviewArtifactMapper.class);
        AiReviewRunRow terminal = new AiReviewRunRow();
        terminal.id = 8001L;
        terminal.taskId = 9001L;
        terminal.attemptNo = 1;
        terminal.status = AiReviewRunStatus.FAILED_RETRYABLE.name();
        terminal.modelName = "model-a";
        terminal.packageHash = "";
        terminal.decision = "";
        terminal.score = 0;
        terminal.retryFromRole = "";
        terminal.summary = "";
        terminal.errorCategory = "PROVIDER";
        terminal.errorMessage = "timeout";
        terminal.version = 4L;
        terminal.createdAt = java.time.OffsetDateTime.now();
        terminal.updatedAt = terminal.createdAt;
        when(runMapper.selectById(8001L)).thenReturn(terminal);
        doAnswer(invocation -> null).when(runMapper).insertRun(any());

        PostgresAiReviewRunStore store = new PostgresAiReviewRunStore(
                runMapper, eventMapper, artifactMapper, SnowflakeIdGenerator.defaultGenerator());
        AiReviewRun child = store.retry("8001", "8002", 200L);

        assertEquals("8001", child.parentRunId());
        assertEquals(2, child.attemptNo());
        assertEquals(AiReviewRunStatus.CREATED, child.status());
    }

    private static AiReviewRunRow copy(AiReviewRunRow source) {
        if (source == null) {
            return null;
        }
        AiReviewRunRow copy = new AiReviewRunRow();
        copy.id = source.id;
        copy.taskId = source.taskId;
        copy.attemptNo = source.attemptNo;
        copy.parentRunId = source.parentRunId;
        copy.status = source.status;
        copy.modelName = source.modelName;
        copy.packageHash = source.packageHash;
        copy.decision = source.decision;
        copy.score = source.score;
        copy.retryFromRole = source.retryFromRole;
        copy.summary = source.summary;
        copy.errorCategory = source.errorCategory;
        copy.errorMessage = source.errorMessage;
        copy.version = source.version;
        copy.createdAt = source.createdAt;
        copy.updatedAt = source.updatedAt;
        copy.expectedStatus = source.expectedStatus;
        copy.expectedVersion = source.expectedVersion;
        return copy;
    }
}
