package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.HostVerificationArtifactRow;
import com.wish.rd.bootstrap.persistence.entity.HostVerificationRunRow;
import com.wish.rd.bootstrap.persistence.entity.HostVerificationStepRow;
import com.wish.rd.bootstrap.persistence.mapper.HostVerificationMapper;
import com.wish.rd.engine.requirement.verify.HostVerificationStore;
import com.wish.rd.engine.requirement.verify.model.HostVerificationArtifact;
import com.wish.rd.engine.requirement.verify.model.HostVerificationRun;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStep;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStepName;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStepStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PostgreSQL adapter and mapper policy for {@link HostVerificationStore}.
 *
 * <p>Uses a mocked mapper that stores rows so the engine contract assertions
 * can run without a live database.
 */
class PostgresHostVerificationStoreTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    void compareAndSetSqlUsesStatusPredicate() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/com/wish/rd/bootstrap/persistence/mapper/HostVerificationMapper.java"));
        assertTrue(mapper.contains("AND status ="));
        assertTrue(mapper.contains("WHERE id = #{row.id}"));
    }

    @Test
    void storeIsTransactionalAndNotAFinalClass() throws Exception {
        assertFalse(Modifier.isFinal(PostgresHostVerificationStore.class.getModifiers()));
        assertNotNull(PostgresHostVerificationStore.class.getMethod(
                "transition",
                String.class,
                HostVerificationStatus.class,
                HostVerificationStatus.class,
                String.class,
                String.class,
                long.class
        ).getAnnotation(Transactional.class));
        String source = Files.readString(Path.of(
                "src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresHostVerificationStore.java"));
        assertFalse(source.contains("LinkedHashMap"),
                "PostgreSQL store must not keep LinkedHashMap as a source of truth");
    }

    @Test
    void ddlKeepsProfileAlterAndAddsRunTables() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/sql/postgres/p15_host_verification.sql"));
        assertTrue(sql.contains("ALTER TABLE rd_qa_validation_profiles"));
        assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS build_commands_json JSONB"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rd_host_verification_runs"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rd_host_verification_steps"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rd_host_verification_artifacts"));
        assertTrue(sql.contains("parent_run_id BIGINT NOT NULL DEFAULT 0"));
        assertTrue(sql.contains("UNIQUE (task_id, attempt_no)"));
    }

    @Test
    void blankParentRunIdIsStoredAsZeroAndReadAsBlank() {
        HostVerificationMapper mapper = mock(HostVerificationMapper.class);
        PostgresHostVerificationStore store = new PostgresHostVerificationStore(mapper, new ObjectMapper());
        store.create(created("8001", "9001", 1));

        ArgumentCaptor<HostVerificationRunRow> captor = ArgumentCaptor.forClass(HostVerificationRunRow.class);
        verify(mapper).insertRun(captor.capture());
        assertEquals(0L, captor.getValue().parentRunId);

        HostVerificationRunRow stored = captor.getValue();
        stored.parentRunId = 0L;
        when(mapper.findById(8001L)).thenReturn(stored);
        assertEquals("", store.find("8001").orElseThrow().parentRunId());
    }

    @Test
    void compareAndSetZeroRowsThrowsStale() {
        HostVerificationMapper mapper = mock(HostVerificationMapper.class);
        HostVerificationRunRow current = new HostVerificationRunRow();
        current.id = 8001L;
        current.taskId = 9001L;
        current.codingStageRunId = 7001L;
        current.parentRunId = 0L;
        current.attemptNo = 1;
        current.status = HostVerificationStatus.CREATED.name();
        current.docsOnly = false;
        current.failureCategory = "";
        current.errorMessage = "";
        current.remediationCount = 0;
        current.createdAt = OffsetDateTime.now();
        when(mapper.findById(8001L)).thenReturn(current);
        when(mapper.compareAndSet(any(), any())).thenReturn(0);
        PostgresHostVerificationStore store = new PostgresHostVerificationStore(mapper, new ObjectMapper());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> store.transition(
                        "8001",
                        HostVerificationStatus.CREATED,
                        HostVerificationStatus.PREPARING,
                        "",
                        "",
                        NOW
                )
        );
        assertTrue(exception.getMessage().contains("stale"), exception.getMessage());
    }

    @Test
    void createThenFindReturnsEqualRun() {
        assertCreateThenFind(fakeStore());
    }

    @Test
    void listByTaskIsOrderedByAttemptNo() {
        assertListByTaskOrdered(fakeStore());
    }

    @Test
    void transitionCreatedToPreparingSucceeds() {
        assertTransitionCreatedToPreparing(fakeStore());
    }

    @Test
    void transitionWithWrongExpectedThrowsStale() {
        assertWrongExpectedThrowsStale(fakeStore());
    }

    @Test
    void terminalRunRefusesFurtherTransition() {
        assertTerminalImmutable(fakeStore());
    }

    @Test
    void saveStepThenListStepsReturnsBuildAndStatic() {
        assertSaveAndListSteps(fakeStore());
    }

    @Test
    void appendArtifactThenListArtifacts() {
        assertAppendAndListArtifacts(fakeStore());
    }

    @Test
    void duplicateAttemptNoOnSameTaskThrows() {
        assertDuplicateAttemptRejected(fakeStore());
    }

    static void assertCreateThenFind(HostVerificationStore store) {
        HostVerificationRun run = created("8001", "9001", 1);
        assertEquals(run, store.create(run));
        assertEquals(run, store.find("8001").orElseThrow());
        assertThrows(IllegalStateException.class, () -> store.create(run));
    }

    static void assertListByTaskOrdered(HostVerificationStore store) {
        store.create(created("8002", "9001", 2));
        store.create(created("8001", "9001", 1));
        store.create(created("8011", "9002", 1));
        assertEquals(
                List.of("8001", "8002"),
                store.listByTask("9001").stream().map(HostVerificationRun::runId).toList()
        );
    }

    static void assertTransitionCreatedToPreparing(HostVerificationStore store) {
        store.create(created("8001", "9001", 1));
        HostVerificationRun next = store.transition(
                "8001",
                HostVerificationStatus.CREATED,
                HostVerificationStatus.PREPARING,
                "",
                "",
                NOW + 5L
        );
        assertEquals(HostVerificationStatus.PREPARING, next.status());
        assertEquals(NOW + 5L, next.startedAtEpochMillis());
        assertEquals(0L, next.finishedAtEpochMillis());
        assertEquals(next, store.find("8001").orElseThrow());
    }

    static void assertWrongExpectedThrowsStale(HostVerificationStore store) {
        store.create(created("8001", "9001", 1));
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> store.transition(
                        "8001",
                        HostVerificationStatus.PREPARING,
                        HostVerificationStatus.BUILDING,
                        "",
                        "",
                        NOW
                )
        );
        assertTrue(exception.getMessage().contains("stale"), exception.getMessage());
        assertEquals(HostVerificationStatus.CREATED, store.find("8001").orElseThrow().status());
    }

    static void assertTerminalImmutable(HostVerificationStore store) {
        store.create(created("8001", "9001", 1));
        store.transition("8001", HostVerificationStatus.CREATED, HostVerificationStatus.PREPARING, "", "", NOW + 1L);
        store.transition("8001", HostVerificationStatus.PREPARING, HostVerificationStatus.BUILDING, "", "", NOW + 2L);
        store.transition(
                "8001",
                HostVerificationStatus.BUILDING,
                HostVerificationStatus.STATIC_CHECKING,
                "",
                "",
                NOW + 3L
        );
        HostVerificationRun succeeded = store.transition(
                "8001",
                HostVerificationStatus.STATIC_CHECKING,
                HostVerificationStatus.SUCCEEDED,
                "",
                "",
                NOW + 4L
        );
        assertEquals(HostVerificationStatus.SUCCEEDED, succeeded.status());
        assertEquals(NOW + 4L, succeeded.finishedAtEpochMillis());
        assertThrows(
                IllegalStateException.class,
                () -> store.transition(
                        "8001",
                        HostVerificationStatus.SUCCEEDED,
                        HostVerificationStatus.CANCELLED,
                        "",
                        "",
                        NOW + 5L
                )
        );
        assertEquals(HostVerificationStatus.SUCCEEDED, store.find("8001").orElseThrow().status());
    }

    static void assertSaveAndListSteps(HostVerificationStore store) {
        store.create(created("8001", "9001", 1));
        HostVerificationStep build = new HostVerificationStep(
                "8001",
                HostVerificationStepName.BUILD,
                HostVerificationStepStatus.SUCCEEDED,
                List.of("mvn -q test"),
                0,
                12L,
                "6001",
                ""
        );
        HostVerificationStep statik = new HostVerificationStep(
                "8001",
                HostVerificationStepName.STATIC,
                HostVerificationStepStatus.FAILED,
                List.of("npm run typecheck"),
                1,
                3L,
                "",
                "typecheck failed"
        );
        store.saveStep(build);
        store.saveStep(statik);
        assertEquals(List.of(build, statik), store.listSteps("8001"));
    }

    static void assertAppendAndListArtifacts(HostVerificationStore store) {
        store.create(created("8001", "9001", 1));
        HostVerificationArtifact artifact = new HostVerificationArtifact(
                "6001",
                "9001",
                "8001",
                "VERIFY_BUILD_LOG",
                "verify-evidence/build.log",
                "s3://rd-qa-evidence/verify/6001",
                "text/plain",
                12L,
                "abc123",
                NOW
        );
        assertEquals(artifact, store.appendArtifact(artifact));
        assertEquals(List.of(artifact), store.listArtifacts("8001"));
    }

    static void assertDuplicateAttemptRejected(HostVerificationStore store) {
        store.create(created("8001", "9001", 1));
        assertThrows(IllegalStateException.class, () -> store.create(created("8002", "9001", 1)));
        assertEquals(1, store.listByTask("9001").size());
        store.create(created("8002", "9002", 1));
        assertEquals("8002", store.find("8002").orElseThrow().runId());
    }

    private static HostVerificationStore fakeStore() {
        Map<Long, HostVerificationRunRow> runs = new LinkedHashMap<>();
        Map<String, HostVerificationStepRow> steps = new LinkedHashMap<>();
        List<HostVerificationArtifactRow> artifacts = new ArrayList<>();
        HostVerificationMapper mapper = mock(HostVerificationMapper.class);
        doAnswer(invocation -> {
            HostVerificationRunRow row = copyRun(invocation.getArgument(0));
            runs.put(row.id, row);
            return null;
        }).when(mapper).insertRun(any());
        when(mapper.findById(anyLong())).thenAnswer(invocation ->
                copyRun(runs.get(((Number) invocation.getArgument(0)).longValue())));
        when(mapper.listByTaskId(anyLong())).thenAnswer(invocation -> {
            long taskId = ((Number) invocation.getArgument(0)).longValue();
            return runs.values().stream()
                    .filter(row -> taskId == row.taskId)
                    .sorted(Comparator.comparingInt(row -> row.attemptNo))
                    .map(PostgresHostVerificationStoreTest::copyRun)
                    .toList();
        });
        when(mapper.compareAndSet(any(), any())).thenAnswer(invocation -> {
            HostVerificationRunRow update = invocation.getArgument(0);
            String expected = invocation.getArgument(1);
            HostVerificationRunRow current = runs.get(update.id);
            if (current == null || !expected.equals(current.status)) {
                return 0;
            }
            runs.put(update.id, copyRun(update));
            return 1;
        });
        doAnswer(invocation -> {
            HostVerificationStepRow row = copyStep(invocation.getArgument(0));
            steps.put(row.runId + ":" + row.step, row);
            return null;
        }).when(mapper).upsertStep(any());
        when(mapper.listSteps(anyLong())).thenAnswer(invocation -> {
            long runId = ((Number) invocation.getArgument(0)).longValue();
            return steps.values().stream()
                    .filter(row -> runId == row.runId)
                    .sorted(Comparator.comparing(row -> row.step))
                    .map(PostgresHostVerificationStoreTest::copyStep)
                    .toList();
        });
        doAnswer(invocation -> {
            artifacts.add(copyArtifact(invocation.getArgument(0)));
            return null;
        }).when(mapper).insertArtifact(any());
        when(mapper.listArtifacts(anyLong())).thenAnswer(invocation -> {
            long runId = ((Number) invocation.getArgument(0)).longValue();
            return artifacts.stream()
                    .filter(row -> runId == row.runId)
                    .map(PostgresHostVerificationStoreTest::copyArtifact)
                    .toList();
        });
        return new PostgresHostVerificationStore(mapper, new ObjectMapper());
    }

    private static HostVerificationRun created(String runId, String taskId, int attemptNo) {
        return new HostVerificationRun(
                runId,
                taskId,
                "7001",
                "",
                attemptNo,
                HostVerificationStatus.CREATED,
                false,
                "",
                "",
                0,
                NOW,
                0L,
                0L
        );
    }

    private static HostVerificationRunRow copyRun(HostVerificationRunRow source) {
        if (source == null) {
            return null;
        }
        HostVerificationRunRow copy = new HostVerificationRunRow();
        copy.id = source.id;
        copy.taskId = source.taskId;
        copy.codingStageRunId = source.codingStageRunId;
        copy.parentRunId = source.parentRunId;
        copy.attemptNo = source.attemptNo;
        copy.status = source.status;
        copy.docsOnly = source.docsOnly;
        copy.failureCategory = source.failureCategory;
        copy.errorMessage = source.errorMessage;
        copy.remediationCount = source.remediationCount;
        copy.createdAt = source.createdAt;
        copy.startedAt = source.startedAt;
        copy.finishedAt = source.finishedAt;
        return copy;
    }

    private static HostVerificationStepRow copyStep(HostVerificationStepRow source) {
        HostVerificationStepRow copy = new HostVerificationStepRow();
        copy.runId = source.runId;
        copy.step = source.step;
        copy.status = source.status;
        copy.commandsJson = source.commandsJson;
        copy.exitCode = source.exitCode;
        copy.durationMillis = source.durationMillis;
        copy.logArtifactId = source.logArtifactId;
        copy.errorMessage = source.errorMessage;
        return copy;
    }

    private static HostVerificationArtifactRow copyArtifact(HostVerificationArtifactRow source) {
        HostVerificationArtifactRow copy = new HostVerificationArtifactRow();
        copy.id = source.id;
        copy.taskId = source.taskId;
        copy.runId = source.runId;
        copy.artifactType = source.artifactType;
        copy.relativePath = source.relativePath;
        copy.objectUri = source.objectUri;
        copy.contentType = source.contentType;
        copy.sizeBytes = source.sizeBytes;
        copy.sha256 = source.sha256;
        copy.createdAt = source.createdAt;
        return copy;
    }
}
