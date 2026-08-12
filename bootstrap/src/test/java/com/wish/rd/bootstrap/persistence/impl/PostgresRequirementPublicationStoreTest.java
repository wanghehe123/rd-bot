package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.RequirementPublicationRow;
import com.wish.rd.bootstrap.persistence.mapper.RequirementPublicationMapper;
import com.wish.rd.engine.requirement.publication.model.RequirementPublication;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresRequirementPublicationStoreTest {

    @Test
    void insertPreparedIsTransactionalAndIdempotentByOperationId() throws Exception {
        Method method = PostgresRequirementPublicationStore.class.getMethod(
                "insertPrepared", RequirementPublication.class);
        assertNotNull(method.getAnnotation(Transactional.class));

        Map<String, RequirementPublicationRow> rows = new LinkedHashMap<>();
        RequirementPublicationMapper mapper = mock(RequirementPublicationMapper.class);
        when(mapper.insertPreparedIfAbsent(any())).thenAnswer(invocation -> {
            RequirementPublicationRow row = invocation.getArgument(0);
            if (rows.containsKey(row.operationId)) {
                return 0;
            }
            rows.put(row.operationId, copy(row));
            return 1;
        });
        when(mapper.selectByOperationId(any())).thenAnswer(invocation ->
                copy(rows.get(invocation.getArgument(0))));

        PostgresRequirementPublicationStore store = new PostgresRequirementPublicationStore(mapper);
        RequirementPublication first = prepared("pub-1", "op-1", "9001");
        RequirementPublication duplicate = prepared("pub-2", "op-1", "9001");

        RequirementPublication created = store.insertPrepared(first);
        RequirementPublication reused = store.insertPrepared(duplicate);

        assertEquals("pub-1", created.id());
        assertEquals("pub-1", reused.id());
        assertEquals(1, rows.size());
    }

    @Test
    void saveUsesExpectedVersionCompareAndSet() {
        Map<String, RequirementPublicationRow> rows = new LinkedHashMap<>();
        RequirementPublicationMapper mapper = mock(RequirementPublicationMapper.class);
        RequirementPublication initial = prepared("pub-1", "op-cas", "9001");
        rows.put(initial.operationId(), row(initial));
        when(mapper.selectByOperationId(any())).thenAnswer(invocation ->
                copy(rows.get(invocation.getArgument(0))));
        when(mapper.updateWithExpectedVersion(any())).thenAnswer(invocation -> {
            RequirementPublicationRow update = invocation.getArgument(0);
            RequirementPublicationRow current = rows.get(update.operationId);
            if (current == null || !current.version.equals(update.expectedVersion)) {
                return 0;
            }
            rows.put(update.operationId, copy(update));
            return 1;
        });

        PostgresRequirementPublicationStore store = new PostgresRequirementPublicationStore(mapper);
        RequirementPublication confirmed = initial.withBranchConfirmed("abc123", 2_000L);
        RequirementPublication saved = store.save(confirmed);

        assertEquals(RequirementPublicationStatus.BRANCH_CONFIRMED, saved.status());
        assertEquals(2, rows.get("op-cas").version.intValue());
        assertEquals("abc123", rows.get("op-cas").remoteHeadSha);

        assertThrows(IllegalStateException.class, () ->
                store.save(initial.withBranchConfirmed("stale", 3_000L)));
    }

    @Test
    void findLatestByTaskIdMapsTheNewestPublicationAndSkipsBlankTaskIds() {
        RequirementPublicationMapper mapper = mock(RequirementPublicationMapper.class);
        RequirementPublication latest = prepared("pub-latest", "op-latest", "9001")
                .withBranchConfirmed("remote-latest", 2_000L);
        when(mapper.selectLatestByTaskId(9001L)).thenReturn(row(latest));
        PostgresRequirementPublicationStore store = new PostgresRequirementPublicationStore(mapper);

        RequirementPublication found = store.findLatestByTaskId("9001").orElseThrow();

        assertEquals("op-latest", found.operationId());
        assertEquals(RequirementPublicationStatus.BRANCH_CONFIRMED, found.status());
        assertEquals("remote-latest", found.remoteHeadSha());
        verify(mapper).selectLatestByTaskId(9001L);
        assertTrue(store.findLatestByTaskId(" ").isEmpty());
    }

    private static RequirementPublication prepared(String id, String operationId, String taskId) {
        return RequirementPublication.prepared(
                id,
                operationId,
                taskId,
                "stage-1",
                "main",
                "requirement/" + taskId,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                1_000L
        );
    }

    private static RequirementPublicationRow row(RequirementPublication publication) {
        RequirementPublicationRow row = new RequirementPublicationRow();
        row.id = publication.id();
        row.operationId = publication.operationId();
        row.taskId = Long.parseLong(publication.taskId());
        row.stageRunId = publication.stageRunId();
        row.status = publication.status().name();
        row.baseBranch = publication.baseBranch();
        row.workBranch = publication.workBranch();
        row.candidatePatchSha256 = publication.candidatePatchSha256();
        row.remoteHeadSha = publication.remoteHeadSha();
        row.pullRequestUrl = publication.pullRequestUrl();
        row.pullRequestNumber = publication.pullRequestNumber();
        row.version = publication.version();
        row.lastError = publication.lastError();
        row.createdAt = OffsetDateTime.now();
        row.updatedAt = row.createdAt;
        return row;
    }

    private static RequirementPublicationRow copy(RequirementPublicationRow source) {
        if (source == null) {
            return null;
        }
        RequirementPublicationRow copy = new RequirementPublicationRow();
        copy.id = source.id;
        copy.operationId = source.operationId;
        copy.taskId = source.taskId;
        copy.stageRunId = source.stageRunId;
        copy.status = source.status;
        copy.baseBranch = source.baseBranch;
        copy.workBranch = source.workBranch;
        copy.candidatePatchSha256 = source.candidatePatchSha256;
        copy.remoteHeadSha = source.remoteHeadSha;
        copy.pullRequestUrl = source.pullRequestUrl;
        copy.pullRequestNumber = source.pullRequestNumber;
        copy.version = source.version;
        copy.lastError = source.lastError;
        copy.nextReconcileAt = source.nextReconcileAt;
        copy.createdAt = source.createdAt;
        copy.updatedAt = source.updatedAt;
        copy.expectedVersion = source.expectedVersion;
        return copy;
    }
}
