package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.RequirementStageCommandRow;
import com.wish.rd.bootstrap.persistence.mapper.RequirementStageCommandMapper;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.model.FairScheduleLimits;
import com.wish.rd.engine.scheduling.model.RequirementDeliverySchedulingPolicy;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Select;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies PostgreSQL stage-command adapter mapping and stale lease handling. */
class PostgresRequirementStageCommandStoreTest {

    @Test
    void shouldFindCommandByDurableId() {
        RequirementStageCommandMapper mapper = mock(RequirementStageCommandMapper.class);
        when(mapper.findById(101L)).thenReturn(row("SUCCEEDED", 1, ""));
        PostgresRequirementStageCommandStore store = new PostgresRequirementStageCommandStore(mapper);

        RequirementStageCommand command = store.findById("101").orElseThrow();

        assertEquals("101", command.commandId());
        assertEquals(RequirementStageCommand.Status.SUCCEEDED, command.status());
        verify(mapper).findById(101L);
    }

    @Test
    void normalLookupNeverSelectsACheckpointBoundGeneration() throws Exception {
        String normalSql = RequirementStageCommandMapper.class.getMethod(
                "find", long.class, String.class, String.class).getAnnotation(Select.class).value()[0];
        assertTrue(normalSql.contains("retry_checkpoint_id IS NULL"));

        RequirementStageCommandMapper mapper = mock(RequirementStageCommandMapper.class);
        RequirementStageCommandRow normal = row("PENDING", 0, "");
        RequirementStageCommandRow retry = row("PENDING", 0, "");
        retry.id = 102L; retry.retryCheckpointId = 401L; retry.businessGeneration = 401L;
        when(mapper.find(201L, "CODING_AGENT", "docker-execute")).thenReturn(normal);
        when(mapper.findByIdentity(201L, "CODING_AGENT", "docker-execute", "401")).thenReturn(retry);
        PostgresRequirementStageCommandStore store = new PostgresRequirementStageCommandStore(mapper);

        assertEquals("101", store.find("201", "CODING_AGENT", "docker-execute").orElseThrow().commandId());
        assertEquals("102", store.find("201", "CODING_AGENT", "docker-execute", "401").orElseThrow().commandId());
    }

    @Test
    void shouldMapAtomicClaimResult() {
        RequirementStageCommandMapper mapper = mock(RequirementStageCommandMapper.class);
        when(mapper.claimOne(
                anyLong(), anyString(), any(OffsetDateTime.class), any(OffsetDateTime.class)
        )).thenReturn(row("RUNNING", 1, "worker-a"));
        PostgresRequirementStageCommandStore store = new PostgresRequirementStageCommandStore(mapper);

        RequirementStageCommand claimed = store.claim("101", "worker-a", 1_000L, 500L).orElseThrow();

        assertEquals(RequirementStageCommand.Status.RUNNING, claimed.status());
        assertEquals(1, claimed.attemptNo());
        verify(mapper).claimOne(
                anyLong(), anyString(), any(OffsetDateTime.class), any(OffsetDateTime.class)
        );
    }

    @Test
    void shouldRejectHeartbeatWhenMapperReportsStaleOwner() {
        RequirementStageCommandMapper mapper = mock(RequirementStageCommandMapper.class);
        when(mapper.heartbeatAttempt(
                anyLong(), anyInt(), anyString(), any(OffsetDateTime.class), any(OffsetDateTime.class)
        )).thenReturn(null);
        PostgresRequirementStageCommandStore store = new PostgresRequirementStageCommandStore(mapper);

        assertThrows(IllegalStateException.class,
                () -> store.heartbeat("101", 1, "stale-owner", 1_000L, 500L));
    }

    @Test
    void shouldMapDeadLetterRetryResult() {
        RequirementStageCommandMapper mapper = mock(RequirementStageCommandMapper.class);
        when(mapper.failAttempt(
                anyLong(), anyInt(), anyString(), anyString(), any(OffsetDateTime.class), any(OffsetDateTime.class)
        )).thenReturn(row("DEAD_LETTERED", 3, ""));
        PostgresRequirementStageCommandStore store = new PostgresRequirementStageCommandStore(mapper);

        RequirementStageCommand failed = store.fail("101", 3, "worker-a", "boom", 1_000L);

        assertEquals(RequirementStageCommand.Status.DEAD_LETTERED, failed.status());
        assertEquals(3, failed.attemptNo());
    }

    @Test
    void shouldRoundTripEveryDurableResourceRequirement() {
        RequirementStageCommandMapper mapper = mock(RequirementStageCommandMapper.class);
        RequirementStageCommandRow row = row("PENDING", 0, "");
        row.stage = "ROLE_EXECUTION:CODING_AGENT";
        row.resourceRequirements = "DOCKER,PROVIDER";
        when(mapper.findById(101L)).thenReturn(row);
        PostgresRequirementStageCommandStore store = new PostgresRequirementStageCommandStore(mapper);

        RequirementStageCommand command = store.findById("101").orElseThrow();

        assertEquals(Set.of(
                com.wish.rd.engine.scheduling.model.ScheduleResourceClass.DOCKER,
                com.wish.rd.engine.scheduling.model.ScheduleResourceClass.PROVIDER),
                command.resourceRequirements());
    }

    @Test
    void shouldRoundTripTheImmutablePolicyAuthorizationReference() {
        RequirementStageCommandMapper mapper = mock(RequirementStageCommandMapper.class);
        RequirementStageCommandRow row = row("PENDING", 0, "");
        row.policyRunId = 301L;
        when(mapper.findById(101L)).thenReturn(row);
        PostgresRequirementStageCommandStore store = new PostgresRequirementStageCommandStore(mapper);

        RequirementStageCommand command = store.findById("101").orElseThrow();

        assertEquals("301", command.policyRunId());
    }

    @Test
    void shouldRoundTripCheckpointGenerationAndExactTargetBinding() {
        RequirementStageCommandMapper mapper = mock(RequirementStageCommandMapper.class);
        RequirementStageCommandRow row = row("PENDING", 0, "");
        row.stage = "ROLE_EXECUTION:CODING_AGENT";
        row.retryCheckpointId = 401L;
        row.businessGeneration = 401L;
        row.targetRetryBindingId = 501L;
        when(mapper.findById(101L)).thenReturn(row);

        RequirementStageCommand command = new PostgresRequirementStageCommandStore(mapper).findById("101").orElseThrow();

        assertEquals("401", command.retryCheckpointId());
        assertEquals(401L, command.businessGeneration());
        assertEquals("501", command.targetRetryBindingId());
    }

    @Test
    void shouldReplanLockedCandidatesUnderOnePostgresFairAdmissionTransaction() {
        RequirementStageCommandMapper mapper = mock(RequirementStageCommandMapper.class);
        long now = System.currentTimeMillis();
        RequirementStageCommandRow candidate = row("PENDING", 0, "");
        candidate.resourceRequirements = "PROVIDER";
        candidate.createdAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(now - 100L), ZoneOffset.UTC);
        candidate.updatedAt = candidate.createdAt;
        candidate.nextVisibleAt = candidate.createdAt;
        candidate.deadlineAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(now + 60_000L), ZoneOffset.UTC);
        RequirementStageCommandRow claimed = row("RUNNING", 1, "worker-a");
        claimed.resourceRequirements = "PROVIDER";
        claimed.createdAt = candidate.createdAt;
        claimed.updatedAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC);
        claimed.nextVisibleAt = candidate.createdAt;
        claimed.deadlineAt = candidate.deadlineAt;
        claimed.leaseUntil = OffsetDateTime.ofInstant(Instant.ofEpochMilli(now + 60_000L), ZoneOffset.UTC);
        when(mapper.lockFairCandidates(anyList(), any())).thenReturn(List.of(candidate));
        when(mapper.inFlightForFairAdmission(any())).thenReturn(List.of());
        when(mapper.claimSelectedBatch(anyList(), anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(claimed));
        PostgresRequirementStageCommandStore store = new PostgresRequirementStageCommandStore(mapper);
        RequirementDeliverySchedulingPolicy policy = new RequirementDeliverySchedulingPolicy(
                new FairScheduleLimits(2, 1, 1, 2, 1, 2, 60_000L),
                Map.of(),
                60_000L,
                "provider-default"
        );

        List<RequirementStageCommand> result = store.claimFairBatch(
                List.of("101"), "worker-a", now, 60_000L, policy);

        assertEquals(List.of("101"), result.stream().map(RequirementStageCommand::commandId).toList());
        verify(mapper).acquireFairAdmissionLock(anyLong());
        verify(mapper).lockFairCandidates(anyList(), any());
        verify(mapper).inFlightForFairAdmission(any());
    }

    @Test
    void claimSqlMustExcludeExhaustedCommandsAndApplyAgingPriority() throws Exception {
        Path sourcePath = Path.of(
                "src/main/java/com/wish/rd/bootstrap/persistence/mapper/RequirementStageCommandMapper.java");
        if (!Files.exists(sourcePath)) {
            sourcePath = Path.of(
                    "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RequirementStageCommandMapper.java");
        }
        String source = Files.readString(sourcePath);

        // W3：claim SQL 现以 `command.` 表别名限定列名（`&lt;`/`&gt;` 为 MyBatis XML 转义），
        // 约束语义不变：exhausted 命令不得被批量 claim、按年龄加权、跳锁、排除零 fence。
        assertTrue(source.contains("command.attempt_no &lt; command.max_attempts"),
                "batch claims must never reclaim an exhausted command");
        assertTrue(source.contains("EXTRACT(EPOCH FROM (#{now} - command.created_at))"),
                "batch claims must apply a bounded age boost before locking rows");
        assertTrue(source.contains("FOR UPDATE SKIP LOCKED"),
                "batch claims must retain cross-instance row-level exclusion");
        assertTrue(source.contains("command.fencing_token &gt; 0"),
                "worker-visible claim and recovery queries must exclude legacy zero-fence commands");
    }

    @Test
    void recoverySqlMustUseConfiguredAgingAndRotateProjectHeads() throws Exception {
        Path sourcePath = Path.of(
                "src/main/java/com/wish/rd/bootstrap/persistence/mapper/RequirementStageCommandMapper.java");
        if (!Files.exists(sourcePath)) {
            sourcePath = Path.of(
                    "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RequirementStageCommandMapper.java");
        }
        String source = Files.readString(sourcePath);

        assertTrue(source.contains("#{agingMillis}"),
                "recovery candidate aging must use the scheduling policy rather than a fixed minute");
        assertTrue(source.contains("project_turn"),
                "recovery candidate ordering must rotate bounded project-head windows");
    }

    @Test
    void rejectsLegacyZeroFenceBeforeMapperInsert() {
        RequirementStageCommandMapper mapper = mock(RequirementStageCommandMapper.class);
        PostgresRequirementStageCommandStore store = new PostgresRequirementStageCommandStore(mapper);
        RequirementStageCommand legacyRead = new RequirementStageCommand(
                "101", "201", 0L, 0L, "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 0, 3,
                0L, com.wish.rd.engine.scheduling.model.ScheduleResourceClass.DOCKER,
                Set.of(com.wish.rd.engine.scheduling.model.ScheduleResourceClass.DOCKER),
                "project-a", "provider-a", 1, RequirementStageCommand.Status.PENDING,
                "", 0L, 1L, "", 1L, 1L);

        assertThrows(IllegalArgumentException.class, () -> store.enqueue(legacyRead));

        verify(mapper, never()).enqueue(any(RequirementStageCommandRow.class));
    }

    @Test
    void rejectsConflictingEffectiveRowReturnedAfterInsertConflict() {
        RequirementStageCommandMapper mapper = mock(RequirementStageCommandMapper.class);
        RequirementStageCommandRow conflicting = row("PENDING", 0, "");
        conflicting.taskVersion = 5L;
        when(mapper.findByIdentity(201L, "CODING_AGENT", "docker-execute", "")).thenReturn(conflicting);
        PostgresRequirementStageCommandStore store = new PostgresRequirementStageCommandStore(mapper);
        RequirementStageCommand requested = RequirementStageCommand.pending(
                "101", "201", 4L, 8L, "CODING_AGENT", "docker-execute", 0, 3,
                60_000L, com.wish.rd.engine.scheduling.model.ScheduleResourceClass.DOCKER,
                "project-a", "provider-a", "P1", 1L);

        assertThrows(IllegalStateException.class, () -> store.enqueue(requested));

        verify(mapper).enqueue(any(RequirementStageCommandRow.class));
        verify(mapper).findByIdentity(201L, "CODING_AGENT", "docker-execute", "");
    }

    private RequirementStageCommandRow row(String status, int attemptNo, String owner) {
        RequirementStageCommandRow row = new RequirementStageCommandRow();
        row.id = 101L;
        row.taskId = 201L;
        row.taskVersion = 4L;
        row.fencingToken = 8L;
        row.role = "CODING_AGENT";
        row.stage = "docker-execute";
        row.attemptNo = attemptNo;
        row.maxAttempts = 3;
        row.deadlineAt = OffsetDateTime.now().plusMinutes(1);
        row.resourceClass = "DOCKER";
        row.resourceRequirements = "DOCKER";
        row.projectId = "project-a";
        row.providerId = "provider-a";
        row.priorityRank = 1;
        row.status = status;
        row.leaseOwner = owner;
        row.leaseUntil = OffsetDateTime.now().plusMinutes(1);
        row.nextVisibleAt = OffsetDateTime.now();
        row.lastError = "";
        row.createdAt = OffsetDateTime.now();
        row.updatedAt = row.createdAt;
        return row;
    }
}
