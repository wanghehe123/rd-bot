package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.RequirementPolicyRunRow;
import com.wish.rd.bootstrap.persistence.mapper.RequirementPolicyRunMapper;
import com.wish.rd.engine.requirement.policy.RequirementPolicyRunStore;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRunState;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRetryContext;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Update;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Contract tests for the PostgreSQL policy ledger's immutable and fenced CAS semantics. */
class PostgresRequirementPolicyRunStoreTest {

    @Test
    void compareAndSetNamesTheRowParameterForEveryBoundColumn() throws Exception {
        String sql = RequirementPolicyRunMapper.class.getMethod("compareAndSet", RequirementPolicyRunRow.class,
                String.class, long.class).getAnnotation(Update.class).value()[0];
        assertTrue(sql.contains("#{row.policyJson}") && sql.contains("#{row.id}"));
    }

    @Test
    void createOrGetAcceptsJsonbNormalizedButCanonicallyIdenticalGeneration() {
        RequirementPolicyRunMapper mapper = mock(RequirementPolicyRunMapper.class);
        RequirementPolicyRunStore store = new PostgresRequirementPolicyRunStore(mapper);
        String plan = "{\"b\":2,\"a\":1}";
        RequirementPolicyRun requested = new RequirementPolicyRun(
                "101", "201", 0L, 1L, plan, RequirementPolicyRun.canonicalJsonDigest(plan),
                "", "", "", RequirementPolicyRunState.PLAN_READY, 0L, 1L, null, null,
                "", "", "", 0L, "", "", 0L, 0L, 1L, 1L);
        RequirementPolicyRunRow normalized = row(requested);
        normalized.planJson = "{\"a\": 1, \"b\": 2}";
        when(mapper.findByGeneration(201L, 0L, 1L)).thenReturn(normalized);

        RequirementPolicyRun effective = store.createOrGet(requested);

        assertEquals("{\"a\": 1, \"b\": 2}", effective.planJson());
        verify(mapper, never()).insertIfAbsent(any());
    }

    @Test
    void createOrGetFailsClosedWhenTheEffectivePersistedGenerationConflicts() {
        RequirementPolicyRunMapper mapper = mock(RequirementPolicyRunMapper.class);
        RequirementPolicyRunStore store = new PostgresRequirementPolicyRunStore(mapper);
        String plan = "{\"plan\":true}";
        RequirementPolicyRun requested = new RequirementPolicyRun(
                "101", "201", 0L, 1L, plan, RequirementPolicyRun.canonicalJsonDigest(plan),
                "", "", "", RequirementPolicyRunState.PLAN_READY, 0L, 1L, null, null,
                "", "", "", 0L, "", "", 0L, 0L, 1L, 1L);
        RequirementPolicyRun conflicting = new RequirementPolicyRun(
                "101", "201", 0L, 1L, "{\"plan\":false}",
                RequirementPolicyRun.canonicalJsonDigest("{\"plan\":false}"), "", "", "",
                RequirementPolicyRunState.PLAN_READY, 0L, 1L, null, null,
                "", "", "", 0L, "", "", 0L, 0L, 1L, 1L);
        when(mapper.findByGeneration(201L, 0L, 1L)).thenReturn(row(conflicting));

        assertThrows(IllegalStateException.class, () -> store.createOrGet(requested));

        verify(mapper, never()).insertIfAbsent(any());
    }

    @Test
    void createOrGetRejectsANewGenerationThatDoesNotStartAtPlanReady() {
        RequirementPolicyRunMapper mapper = mock(RequirementPolicyRunMapper.class);
        RequirementPolicyRunStore store = new PostgresRequirementPolicyRunStore(mapper);

        assertThrows(IllegalStateException.class, () -> store.createOrGet(waiting()));

        verify(mapper, never()).insertIfAbsent(any());
    }

    @Test
    void compareAndSetRejectsAnInvalidWaitingApprovalToDirectAppliedTransitionBeforeUpdate() {
        RequirementPolicyRunMapper mapper = mock(RequirementPolicyRunMapper.class);
        RequirementPolicyRunStore store = new PostgresRequirementPolicyRunStore(mapper);
        RequirementPolicyRun waiting = waiting();
        when(mapper.lockForUpdate(101L)).thenReturn(row(waiting));
        RequirementPolicyRun directApplied = new RequirementPolicyRun(
                waiting.id(), waiting.taskId(), waiting.sourceTaskVersion(), waiting.sourceFencingToken(),
                waiting.planJson(), waiting.planDigest(), waiting.policyJson(), waiting.policyDigest(),
                "ALLOWED", RequirementPolicyRunState.APPLIED, 5L,
                7L, null, null, "", "", "", 0L, "", "901", 2L,
                waiting.ledgerVersion() + 1L, waiting.createdAtEpochMillis(), 2L);

        assertThrows(IllegalStateException.class, () -> store.compareAndSet(
                directApplied, RequirementPolicyRunState.WAITING_APPROVAL, waiting.ledgerVersion()));

        verify(mapper, never()).compareAndSet(any(), any(), any(Long.class));
    }

    @Test
    void compareAndSetAcceptsJsonbNormalizedImmutablePolicyAndPlan() {
        RequirementPolicyRunMapper mapper = mock(RequirementPolicyRunMapper.class);
        RequirementPolicyRunStore store = new PostgresRequirementPolicyRunStore(mapper);
        RequirementPolicyRun decided = decided();
        RequirementPolicyRunRow normalized = row(decided);
        normalized.planJson = "{\"plan\": true}";
        normalized.policyJson = "{\"action\": \"WAITING_APPROVAL\"}";
        RequirementPolicyRun waiting = new RequirementPolicyRun(
                decided.id(), decided.taskId(), decided.sourceTaskVersion(), decided.sourceFencingToken(),
                decided.planJson(), decided.planDigest(), decided.policyJson(), decided.policyDigest(),
                decided.policyAction(), RequirementPolicyRunState.WAITING_APPROVAL,
                5L, 7L, null, null,
                "", "", "", 0L, "", "", 0L, decided.ledgerVersion() + 1L,
                decided.createdAtEpochMillis(), 2L);
        when(mapper.lockForUpdate(101L)).thenReturn(normalized);
        when(mapper.compareAndSet(any(), eq("POLICY_DECIDED"), eq(1L))).thenReturn(1);

        assertEquals(waiting, store.compareAndSet(waiting, RequirementPolicyRunState.POLICY_DECIDED, 1L));
    }

    @Test
    void compareAndSetRejectsChangingTheStoredApprovalConcurrencyPairBeforeUpdate() {
        RequirementPolicyRunMapper mapper = mock(RequirementPolicyRunMapper.class);
        RequirementPolicyRunStore store = new PostgresRequirementPolicyRunStore(mapper);
        RequirementPolicyRun approved = approved();
        when(mapper.lockForUpdate(101L)).thenReturn(row(approved));
        assertThrows(IllegalArgumentException.class, () -> new RequirementPolicyRun(
                approved.id(), approved.taskId(), approved.sourceTaskVersion(), approved.sourceFencingToken(),
                approved.planJson(), approved.planDigest(), approved.policyJson(), approved.policyDigest(),
                approved.policyAction(), RequirementPolicyRunState.APPLIED, 7L,
                9L, 5L, 8L, approved.approvalRequestId(), approved.approvedBy(),
                approved.note(), approved.approvedAtEpochMillis(), approved.approvalResumeCommandId(), "901", 2L,
                approved.ledgerVersion() + 1L, approved.createdAtEpochMillis(), 2L));

        verify(mapper, never()).compareAndSet(any(), any(), any(Long.class));
    }

    @Test
    void supersedesEveryEligibleActivePolicyShapeAndPreservesApprovedAuditMetadata() {
        for (RequirementPolicyRun source : List.of(planReady(), decided(), waiting(), approved())) {
            RequirementPolicyRunMapper mapper = mock(RequirementPolicyRunMapper.class);
            RequirementPolicyRunStore store = new PostgresRequirementPolicyRunStore(mapper);
            when(mapper.lockForUpdate(101L)).thenReturn(row(source));
            when(mapper.compareAndSet(any(), eq(source.state().name()), eq(source.ledgerVersion()))).thenReturn(1);

            RequirementPolicyRun superseded = store.supersedeForPolicyRetry(retryContext(source),
                    source.ledgerVersion(), 9L);

            assertEquals(RequirementPolicyRunState.SUPERSEDED, superseded.state());
            assertEquals(source.ledgerVersion() + 1L, superseded.ledgerVersion());
            assertEquals(source.createdAtEpochMillis(), superseded.createdAtEpochMillis());
            if (source.state() == RequirementPolicyRunState.APPROVED) {
                assertEquals(source.approvalRequestId(), superseded.approvalRequestId());
                assertEquals(source.approvedBy(), superseded.approvedBy());
                assertEquals(source.note(), superseded.note());
                assertEquals(source.approvedAtEpochMillis(), superseded.approvedAtEpochMillis());
            }
        }
    }

    @Test
    void supersessionRejectsStaleVersionOrDigestBeforeMapperUpdate() {
        RequirementPolicyRunMapper mapper = mock(RequirementPolicyRunMapper.class);
        RequirementPolicyRunStore store = new PostgresRequirementPolicyRunStore(mapper);
        RequirementPolicyRun source = waiting();
        when(mapper.lockForUpdate(101L)).thenReturn(row(source));

        assertThrows(IllegalStateException.class, () -> store.supersedeForPolicyRetry(
                retryContext(source), source.ledgerVersion() + 1L, 9L));
        RequirementPolicyRetryContext wrongDigest = new RequirementPolicyRetryContext("101", 101L,
                "binding-reviewer", source.id(), "sha256:" + "b".repeat(64), TaskRetryCheckpointStatus.DISPATCHED);
        assertThrows(IllegalStateException.class, () -> store.supersedeForPolicyRetry(
                wrongDigest, source.ledgerVersion(), 9L));

        verify(mapper, never()).compareAndSet(any(), any(), any(Long.class));
    }

    @Test
    void supersessionRejectsTerminalPolicySourcesBeforeMapperUpdate() {
        for (RequirementPolicyRun source : List.of(applied(), denied(), waiting().superseded(9L))) {
            RequirementPolicyRunMapper mapper = mock(RequirementPolicyRunMapper.class);
            RequirementPolicyRunStore store = new PostgresRequirementPolicyRunStore(mapper);
            when(mapper.lockForUpdate(101L)).thenReturn(row(source));

            assertThrows(IllegalStateException.class, () -> store.supersedeForPolicyRetry(
                    retryContext(source), source.ledgerVersion(), 10L));

            verify(mapper, never()).compareAndSet(any(), any(), any(Long.class));
        }
    }

    @Test
    void supersessionFailsClosedWhenMapperCasLoses() {
        RequirementPolicyRunMapper mapper = mock(RequirementPolicyRunMapper.class);
        RequirementPolicyRunStore store = new PostgresRequirementPolicyRunStore(mapper);
        RequirementPolicyRun source = approved();
        when(mapper.lockForUpdate(101L)).thenReturn(row(source));
        when(mapper.compareAndSet(any(), eq("APPROVED"), eq(source.ledgerVersion()))).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> store.supersedeForPolicyRetry(
                retryContext(source), source.ledgerVersion(), 9L));
    }

    private static RequirementPolicyRun planReady() {
        String plan = "{\"plan\":true}";
        return new RequirementPolicyRun("101", "201", 3L, 5L, plan,
                RequirementPolicyRun.canonicalJsonDigest(plan), "", "", "", RequirementPolicyRunState.PLAN_READY,
                3L, 5L, null, null, "", "", "", 0L, "", "", 0L, 1L, 1L, 1L);
    }

    private static RequirementPolicyRun applied() {
        RequirementPolicyRun source = decided();
        return new RequirementPolicyRun(source.id(), source.taskId(), source.sourceTaskVersion(), source.sourceFencingToken(),
                source.planJson(), source.planDigest(), source.policyJson(), source.policyDigest(), "ALLOWED",
                RequirementPolicyRunState.APPLIED, 5L, 7L, null, null, "", "", "", 0L,
                "", "901", 2L, 2L, source.createdAtEpochMillis(), 2L);
    }

    private static RequirementPolicyRun denied() {
        RequirementPolicyRun source = decided();
        return new RequirementPolicyRun(source.id(), source.taskId(), source.sourceTaskVersion(), source.sourceFencingToken(),
                source.planJson(), source.planDigest(), source.policyJson(), source.policyDigest(), "UNSAFE",
                RequirementPolicyRunState.DENIED, 5L, 7L, null, null, "", "", "", 0L,
                "", "901", 2L, 2L, source.createdAtEpochMillis(), 2L);
    }

    private static RequirementPolicyRetryContext retryContext(RequirementPolicyRun source) {
        return new RequirementPolicyRetryContext("101", 101L, "binding-reviewer", source.id(),
                source.planDigest(), TaskRetryCheckpointStatus.DISPATCHED);
    }

    private static RequirementPolicyRun waiting() {
        String plan = "{\"plan\":true}";
        String policy = "{\"action\":\"WAITING_APPROVAL\"}";
        return new RequirementPolicyRun(
                "101", "201", 3L, 5L, plan, RequirementPolicyRun.canonicalJsonDigest(plan), policy,
                RequirementPolicyRun.canonicalJsonDigest(policy), "WAITING_APPROVAL",
                RequirementPolicyRunState.WAITING_APPROVAL, 5L, 7L, null, null,
                "", "", "", 0L, "", "", 0L, 1L, 1L, 1L);
    }

    private static RequirementPolicyRun decided() {
        RequirementPolicyRun waiting = waiting();
        return new RequirementPolicyRun(
                waiting.id(), waiting.taskId(), waiting.sourceTaskVersion(), waiting.sourceFencingToken(),
                waiting.planJson(), waiting.planDigest(), waiting.policyJson(), waiting.policyDigest(),
                waiting.policyAction(), RequirementPolicyRunState.POLICY_DECIDED,
                4L, 6L, null, null,
                "", "", "", 0L, "", "", 0L, 1L, 1L, 1L);
    }

    private static RequirementPolicyRun approved() {
        RequirementPolicyRun waiting = waiting();
        return new RequirementPolicyRun(
                waiting.id(), waiting.taskId(), waiting.sourceTaskVersion(), waiting.sourceFencingToken(),
                waiting.planJson(), waiting.planDigest(), waiting.policyJson(), waiting.policyDigest(),
                waiting.policyAction(), RequirementPolicyRunState.APPROVED, 6L, 8L, 5L, 7L,
                "approval-1", "host", "safe", 2L, "901", "", 0L, 2L, 1L, 2L);
    }

    private static RequirementPolicyRunRow row(RequirementPolicyRun run) {
        RequirementPolicyRunRow row = new RequirementPolicyRunRow();
        row.id = PostgresPersistenceSupport.parseId(run.id());
        row.taskId = PostgresPersistenceSupport.parseId(run.taskId());
        row.sourceTaskVersion = run.sourceTaskVersion();
        row.sourceFence = run.sourceFencingToken();
        row.planJson = run.planJson();
        row.planDigest = run.planDigest();
        row.policyJson = run.policyJson();
        row.policyDigest = run.policyDigest();
        row.policyAction = run.policyAction();
        row.state = run.state().name();
        row.boundTaskVersion = run.boundTaskVersion();
        row.boundFence = run.boundFencingToken();
        row.approvalExpectedTaskVersion = run.approvalExpectedTaskVersion();
        row.approvalExpectedFence = run.approvalExpectedFencingToken();
        row.approvalRequestId = run.approvalRequestId();
        row.approvedBy = run.approvedBy();
        row.note = run.note();
        row.approvedAt = PostgresPersistenceSupport.toDateTime(run.approvedAtEpochMillis());
        row.approvalResumeCommandId = run.approvalResumeCommandId().isBlank()
                ? null : PostgresPersistenceSupport.parseId(run.approvalResumeCommandId());
        row.consumedByCommandId = run.consumedByCommandId().isBlank()
                ? null : PostgresPersistenceSupport.parseId(run.consumedByCommandId());
        row.consumedAt = PostgresPersistenceSupport.toDateTime(run.consumedAtEpochMillis());
        row.ledgerVersion = run.ledgerVersion();
        row.createdAt = PostgresPersistenceSupport.toDateTime(run.createdAtEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(run.updatedAtEpochMillis());
        return row;
    }
}
