package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.RdTaskRow;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskMapper;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class PostgresRdTaskStoreCasTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void unfencedStatusWriteFailsClosed() {
        RdTaskMapper mapper = mock(RdTaskMapper.class);
        PostgresRdTaskStore store = new PostgresRdTaskStore(mapper);

        assertThrows(UnsupportedOperationException.class, () -> store.advanceStatusWithExpectedVersion(
                "42", 3L, RdTaskStatus.EXECUTING, RdTaskStatus.COMPLETED, null,
                "{\"status\":\"SUCCESS\"}", "https://example.com/pr/1"));
    }

    @Test
    void fencedStatusWriteUsesVersionStatusAndFencingToken() {
        RdTaskMapper mapper = mock(RdTaskMapper.class);
        AtomicLong storedVersion = new AtomicLong(3L);
        when(mapper.advanceStatusWithExpectedVersionFenced(
                eq(42L), eq(3L), eq(8L), eq("EXECUTING"), eq("COMPLETED"),
                any(), any(), any(), any(), any(OffsetDateTime.class)
        )).thenAnswer(invocation -> {
            long expected = invocation.getArgument(1);
            if (storedVersion.get() != expected) {
                return 0;
            }
            storedVersion.incrementAndGet();
            return 1;
        });

        PostgresRdTaskStore store = new PostgresRdTaskStore(mapper);

        assertDoesNotThrow(() -> store.advanceStatusWithExpectedVersion(
                "42", 3L, 8L, RdTaskStatus.EXECUTING, RdTaskStatus.COMPLETED,
                null, "{\"status\":\"SUCCESS\"}", "https://example.com/pr/1", null));

        assertThrows(IllegalStateException.class, () -> store.advanceStatusWithExpectedVersion(
                "42", 3L, 8L, RdTaskStatus.COMPLETED, RdTaskStatus.FAILED_NEEDS_HUMAN,
                "stale", null, null, null));
    }

    @Test
    void shouldReadStoredVersionForFencingCallers() {
        RdTaskMapper mapper = mock(RdTaskMapper.class);
        RdTaskRow row = new RdTaskRow();
        row.id = 7L;
        row.version = 4L;
        when(mapper.selectById(7L)).thenReturn(row);

        PostgresRdTaskStore store = new PostgresRdTaskStore(mapper);

        assertEquals(Optional.of(4L), store.findVersion("7"));
        assertEquals(Optional.empty(), store.findVersion("8"));
    }

    @Test
    void legacyNullFenceIsVisibleAsZeroAndCannotAuthorizeExistingWrites() {
        RdTaskMapper mapper = mock(RdTaskMapper.class);
        RdTaskRow legacyRequirement = requirementRow(51L, 3L, null);
        when(mapper.selectById(51L)).thenReturn(legacyRequirement);
        PostgresRdTaskStore store = new PostgresRdTaskStore(mapper);
        RdRequirementTask zeroFenceCaller = new RdRequirementTask(
                "51", RdRequirementTask.TASK_TYPE, "ADMIN", "", "", "P1", RdTaskStatus.CREATED,
                "legacy", "project", "P", "Project", "https://example.test/repo", "owner", "repo", "main", "",
                "result", "[]", "", "{}", "", "", 1L, 2L, false, 0L, 3L, 0L);

        assertEquals(Optional.of(0L), store.findFencingToken("51"));
        assertEquals(0L, store.findRequirementTask("51").orElseThrow().fencingToken());
        assertThrows(IllegalStateException.class, () -> store.saveRequirementTask(zeroFenceCaller));
        assertThrows(IllegalArgumentException.class, () -> store.updateTaskWithExpectedVersion(
                zeroFenceCaller, 3L, 0L, RdTaskStatus.CREATED));
        assertThrows(IllegalArgumentException.class, () -> store.advanceStatusWithExpectedVersion(
                "51", 3L, 0L, RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING,
                "", "{}", "", ""));

        verify(mapper, never()).updateTaskWithExpectedVersionFenced(any(), anyLong(), anyLong(), anyString());
        verify(mapper, never()).advanceStatusWithExpectedVersionFenced(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void existingFenceOneRejectsZeroFenceBugFixSnapshotWithoutUpdating() {
        RdTaskMapper mapper = mock(RdTaskMapper.class);
        when(mapper.selectById(52L)).thenReturn(bugFixRow(52L, 4L, 1L));
        PostgresRdTaskStore store = new PostgresRdTaskStore(mapper);
        RdBugFixTask zeroFenceCaller = RdBugFixTask.created("52", "ticket-52", "legacy", "P1", 1L)
                .withConcurrency(4L, 0L);

        assertThrows(IllegalStateException.class, () -> store.saveBugFixTask(zeroFenceCaller));

        verify(mapper, never()).updateTaskWithExpectedVersionFenced(any(), anyLong(), anyLong(), anyString());
    }

    @Test
    void shouldPersistMetadataPauseAndDeleteThroughFencedSnapshotCas() {
        RdTaskMapper mapper = mock(RdTaskMapper.class);
        when(mapper.updateTaskWithExpectedVersionFenced(
                any(RdTaskRow.class), eq(3L), eq(8L), eq("EXECUTING"))).thenReturn(1);
        PostgresRdTaskStore store = new PostgresRdTaskStore(mapper);

        RdBugFixTask next = new RdBugFixTask(
                "42", RdBugFixTask.TASK_TYPE, "ticket-42", "updated ticket", "P0",
                RdTaskStatus.DELETED, "", "updated title", "", "{}", "", "", "project",
                "project-key", "Project", "https://example.com/repo.git", "owner", "repo", "main",
                1L, 4L, true, 3L, 8L);

        store.updateTaskWithExpectedVersion(next, 3L, 8L, RdTaskStatus.EXECUTING);
    }

    @Test
    void shouldRejectStaleMetadataSnapshot() {
        RdTaskMapper mapper = mock(RdTaskMapper.class);
        when(mapper.updateTaskWithExpectedVersionFenced(
                any(RdTaskRow.class), anyLong(), anyLong(), anyString())).thenReturn(0);
        PostgresRdTaskStore store = new PostgresRdTaskStore(mapper);

        RdBugFixTask next = RdBugFixTask.created("43", "ticket-43", "title", "P1", 1L);

        assertThrows(IllegalStateException.class,
                () -> store.updateTaskWithExpectedVersion(next, 2L, 7L, RdTaskStatus.CREATED));
    }

    @Test
    void requirementMetadataCasPreservesDeliveryFieldsAndPassesConcurrency() {
        RdTaskMapper mapper = mock(RdTaskMapper.class);
        when(mapper.updateTaskWithExpectedVersionFenced(
                any(RdTaskRow.class), eq(7L), eq(13L), eq("CREATED"))).thenReturn(1);
        PostgresRdTaskStore store = new PostgresRdTaskStore(mapper);

        RdRequirementTask original = RdRequirementTask.created(
                "1784100000005",
                new CreateRequirementTaskCommand(
                        "原始需求标题", "P1", "ADMIN", "source-5", "https://example.test/req/5",
                        "project-5", "PROJ-5", "项目五", "https://github.com/example/repo.git",
                        "owner", "repo", "main", "交付需求五", List.of("验收标准五"), List.of(), false, 512L),
                1_784_100_000_000L)
                .withConcurrency(7L, 13L)
                .withEditedFields("编辑后的需求标题", "P0", 1_784_100_000_001L);

        store.updateTaskWithExpectedVersion(original, 7L, 13L, RdTaskStatus.CREATED);

        ArgumentCaptor<RdTaskRow> rowCaptor = ArgumentCaptor.forClass(RdTaskRow.class);
        verify(mapper).updateTaskWithExpectedVersionFenced(
                rowCaptor.capture(), eq(7L), eq(13L), eq("CREATED"));
        RdTaskRow row = rowCaptor.getValue();
        assertEquals(RdRequirementTask.TASK_TYPE, row.taskType);
        assertEquals("编辑后的需求标题", row.title);
        assertEquals("P0", row.priority);
        assertEquals("project-5", row.projectId);
        assertEquals("PROJ-5", row.projectKey);
        assertEquals("项目五", row.projectName);
        assertEquals("交付需求五", row.expectedResult);
        assertEquals("[\"验收标准五\"]", row.acceptanceCriteriaJson);
        assertEquals(7L, row.version);
        assertEquals(13L, row.fencingToken);
    }

    @Test
    void shouldRoundTripStructuredHostAssertionInputWithRequirementTask() throws Exception {
        RdTaskMapper mapper = mock(RdTaskMapper.class);
        AtomicReference<RdTaskRow> persisted = new AtomicReference<>();
        when(mapper.selectById(1_784_100_000_006L)).thenAnswer(ignored -> persisted.get());
        doAnswer(invocation -> {
            persisted.set(invocation.getArgument(0));
            return null;
        }).when(mapper).upsertTask(any(RdTaskRow.class));
        PostgresRdTaskStore store = new PostgresRdTaskStore(mapper);
        JsonNode bundle = OBJECT_MAPPER.readTree("""
                {
                  "assertions": [
                    {
                      "scope": "CURRENT",
                      "id": "current-file",
                      "assertionType": "FILE_EXISTS",
                      "target": "README.md",
                      "operator": "exists"
                    },
                    {
                      "scope": "REGRESSION",
                      "id": "regression-file",
                      "assertionType": "FILE_EXISTS",
                      "target": "README.md",
                      "operator": "exists"
                    }
                  ]
                }
                """);
        RdRequirementTask task = RdRequirementTask.created(
                "1784100000006",
                new CreateRequirementTaskCommand(
                        "Host assertion persistence", "P1", "ADMIN", "", "", "project-6", "P6", "项目六",
                        "https://github.com/example/repo.git", "owner", "repo", "main", "交付结构化断言",
                        List.of("human-readable criterion"), List.of(), false, 0L, bundle),
                1_784_100_000_006L
        );

        RdRequirementTask reloaded = store.saveRequirementTask(task);

        assertEquals("CURRENT", reloaded.hostAssertionBundle().path("assertions").get(0).path("scope").asText());
        assertEquals("REGRESSION", reloaded.hostAssertionBundle().path("assertions").get(1).path("scope").asText());
        assertEquals("[\"human-readable criterion\"]", reloaded.acceptanceCriteriaJson());
        assertEquals("CURRENT", OBJECT_MAPPER.readTree(persisted.get().hostAssertionBundleJson)
                .path("assertions").get(0).path("scope").asText());
        assertEquals(1L, persisted.get().fencingToken);
        assertEquals(1L, reloaded.fencingToken());
    }

    @Test
    void taskListSnapshotOmitsPromptAndExecutionBlobs() {
        assertEquals(false, PostgresRdTaskStore.includeColumnInTaskList("prompt_snapshot"));
        assertEquals(false, PostgresRdTaskStore.includeColumnInTaskList("execution_result_json"));
        assertEquals(true, PostgresRdTaskStore.includeColumnInTaskList("title"));
        assertEquals(true, PostgresRdTaskStore.includeColumnInTaskList("status"));
    }

    @Test
    void findAdminShellDoesNotSelectPromptOrExecutionBlobs() {
        RdTaskMapper mapper = mock(RdTaskMapper.class);
        RdTaskRow row = requirementRow(8L, 1L, 1L);
        row.title = "shell-row";
        row.promptSnapshot = null;
        row.executionResultJson = null;
        row.createdAt = OffsetDateTime.parse("2026-08-20T00:00:00Z");
        row.updatedAt = OffsetDateTime.parse("2026-08-20T00:00:00Z");
        when(mapper.selectOne(any())).thenReturn(row);

        PostgresRdTaskStore store = new PostgresRdTaskStore(mapper);
        Optional<com.wish.rd.rag.runtime.model.RdTask> found = store.findAdminShell("8");

        assertEquals(true, found.isPresent());
        assertEquals("", ((RdRequirementTask) found.get()).promptSnapshot());
        verify(mapper).selectOne(any());
        verify(mapper, never()).selectById(any());
    }

    @Test
    void listRequirementTasksMapsMissingBlobsToEmpty() {
        RdTaskMapper mapper = mock(RdTaskMapper.class);
        RdTaskRow row = requirementRow(7L, 1L, 1L);
        row.title = "list-row";
        row.promptSnapshot = null;
        row.executionResultJson = null;
        row.createdAt = OffsetDateTime.parse("2026-08-20T00:00:00Z");
        row.updatedAt = OffsetDateTime.parse("2026-08-20T00:00:00Z");
        when(mapper.selectList(any())).thenReturn(List.of(row));

        PostgresRdTaskStore store = new PostgresRdTaskStore(mapper);
        List<RdRequirementTask> tasks = store.listRequirementTasks();

        assertEquals(1, tasks.size());
        assertEquals("", tasks.get(0).promptSnapshot());
        assertEquals("{}", tasks.get(0).executionResultJson());
        assertEquals("list-row", tasks.get(0).title());
    }

    private static RdTaskRow requirementRow(Long id, Long version, Long fencingToken) {
        RdTaskRow row = new RdTaskRow();
        row.id = id;
        row.taskType = RdRequirementTask.TASK_TYPE;
        row.status = RdTaskStatus.CREATED.name();
        row.version = version;
        row.fencingToken = fencingToken;
        row.executionResultJson = "{}";
        row.acceptanceCriteriaJson = "[]";
        return row;
    }

    private static RdTaskRow bugFixRow(Long id, Long version, Long fencingToken) {
        RdTaskRow row = requirementRow(id, version, fencingToken);
        row.taskType = RdBugFixTask.TASK_TYPE;
        return row;
    }
}
