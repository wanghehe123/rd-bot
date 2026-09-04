package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.RequirementPolicyRunRow;
import com.wish.rd.bootstrap.persistence.entity.RequirementStageCommandRow;
import com.wish.rd.bootstrap.persistence.entity.RdTaskRow;
import com.wish.rd.bootstrap.persistence.entity.RdTaskStatusEventRow;
import com.wish.rd.bootstrap.persistence.mapper.RequirementPolicyRunMapper;
import com.wish.rd.bootstrap.persistence.mapper.RequirementStageCommandMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskStatusEventMapper;
import com.wish.rd.bootstrap.threading.RequirementStageCommandFactory;
import com.wish.rd.engine.requirement.audit.AuditedTaskState;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateCodec;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateInitializer;
import com.wish.rd.engine.requirement.audit.impl.InMemoryAuditedTaskStateStore;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.policy.model.ApproveRequirementPolicyCommand;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApprovalResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApplyDisposition;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApplyResult;
import com.wish.rd.engine.requirement.policy.model.RecordRequirementPolicyDecisionCommand;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationProposal;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationPreparationResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyResumeResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRunState;
import com.wish.rd.engine.scheduling.model.RequirementDeliverySchedulingPolicy;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mock contract for approval ordering and exception propagation; it does not prove database
 * rollback, which is supplied by the adapter's {@code @Transactional} boundary in integration QA.
 */
class PostgresRequirementPolicyTransactionAdapterTest {
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void approvalAndResumeUseActiveClassProxyTransactions() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                PolicyTransactionClassProxyConfiguration.class
        )) {
            PostgresRequirementPolicyTransactionAdapter adapter = context.getBean(
                    PostgresRequirementPolicyTransactionAdapter.class);
            PolicyMapperTransactionProbe probe = context.getBean(PolicyMapperTransactionProbe.class);
            RecordingTransactionManager transactionManager = context.getBean(RecordingTransactionManager.class);

            assertTrue(AopUtils.isCglibProxy(adapter));
            assertThrows(IllegalStateException.class, () -> adapter.approve(command("host", "safe"), "host", NOW));
            assertThrows(IllegalStateException.class, () -> adapter.consumeApproval(
                    runningResumeCommand(6L, 8L, "worker-1"), "worker-1", NOW));

            assertTrue(probe.approveSawActiveTransaction.get());
            assertTrue(probe.consumeSawActiveTransaction.get());
            assertEquals(2, transactionManager.begins.get());
            assertEquals(2, transactionManager.rollbacks.get());
        }
    }

    @Test
    void approvesUnderPolicyThenTaskLocksAndBindsTheExactEnqueuedResumeCommand() {
        Fixture fixture = fixture();
        RequirementStageCommand resume = resumeCommand(6L, 8L);
        when(fixture.taskMapper.advanceStatusWithExpectedVersionFenced(
                any(Long.class), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(fixture.policyMapper.compareAndSet(any(), eq("WAITING_APPROVAL"), eq(3L))).thenReturn(1);
        when(fixture.eventMapper.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(fixture.commandFactory.createPendingCommand(
                "201", 6L, 8L, "REQUIREMENT_DELIVERY", "APPROVAL_RESUME", "project-1", "P1", "", "101", NOW))
                .thenReturn(resume);
        when(fixture.commandMapper.enqueue(any())).thenReturn(1);

        RequirementPolicyApprovalResult result = fixture.adapter.approve(command("host", "safe"), "host", NOW);

        assertSame(resume, result.approvalResumeCommand());
        assertEquals(6L, result.taskVersion());
        assertEquals(8L, result.fencingToken());
        var ordered = inOrder(fixture.policyMapper, fixture.taskMapper, fixture.eventMapper,
                fixture.commandFactory, fixture.commandMapper);
        ordered.verify(fixture.policyMapper).lockForUpdate(101L);
        ordered.verify(fixture.taskMapper).lockByIdForUpdate(201L);
        ordered.verify(fixture.taskMapper).advanceStatusWithExpectedVersionFenced(
                201L, 5L, 7L, "WAITING_APPROVAL", "WAITING_APPROVAL",
                null, null, null, null, PostgresPersistenceSupport.toDateTime(NOW));
        ordered.verify(fixture.eventMapper).insert(any(RdTaskStatusEventRow.class));
        ordered.verify(fixture.commandFactory).createPendingCommand(
                "201", 6L, 8L, "REQUIREMENT_DELIVERY", "APPROVAL_RESUME", "project-1", "P1", "", "101", NOW);
        ordered.verify(fixture.commandMapper).enqueue(any());
        ordered.verify(fixture.policyMapper).compareAndSet(any(), eq("WAITING_APPROVAL"), eq(3L));
        ArgumentCaptor<RequirementPolicyRunRow> ledger = ArgumentCaptor.forClass(RequirementPolicyRunRow.class);
        verify(fixture.policyMapper).compareAndSet(ledger.capture(), eq("WAITING_APPROVAL"), eq(3L));
        assertEquals(5L, ledger.getValue().approvalExpectedTaskVersion);
        assertEquals(7L, ledger.getValue().approvalExpectedFence);
        assertEquals(6L, ledger.getValue().boundTaskVersion);
        assertEquals(8L, ledger.getValue().boundFence);
        assertEquals("request-1", ledger.getValue().approvalRequestId);
        assertEquals(901L, ledger.getValue().approvalResumeCommandId);
        assertEquals("host", ledger.getValue().approvedBy);
        assertEquals("safe", ledger.getValue().note);
    }

    @Test
    void preparesCanonicalPlanReadyLedgerBeforeItsFkBoundEvaluateCommandAndReplaysJsonbSemantically() {
        Fixture fixture = fixture();
        fixture.task.status = "PLAN_GENERATED";
        RequirementStageCommand evaluate = commandWith("101", "REQUIREMENT_DELIVERY", "POLICY_EVALUATE", 5L, 7L);
        String canonicalPlan = "{\"plan\":true}";
        String policy = "{\"policyAction\":\"ALLOWED\",\"reason\":\"safe\"}";
        RequirementPolicyEvaluationProposal proposal = new RequirementPolicyEvaluationProposal(
                "201", 5L, 7L, canonicalPlan, RequirementPolicyRun.canonicalJsonDigest(canonicalPlan),
                policy, RequirementPolicyRun.canonicalJsonDigest(policy), "ALLOWED");
        RequirementPolicyRun ready = planReady();
        RequirementPolicyRunRow jsonbRoundTrip = policyRow(ready);
        jsonbRoundTrip.planJson = "{\"plan\": true}";
        when(fixture.policyMapper.findById(101L)).thenReturn(null, jsonbRoundTrip);
        when(fixture.policyMapper.insertIfAbsent(any())).thenReturn(1);
        when(fixture.commandMapper.find(201L, "REQUIREMENT_DELIVERY", "POLICY_EVALUATE"))
                .thenReturn(null, commandRow(evaluate));
        when(fixture.commandMapper.enqueue(any())).thenReturn(1);

        RequirementPolicyEvaluationPreparationResult prepared = fixture.adapter.prepareEvaluation(proposal, evaluate, NOW);

        assertEquals("101", prepared.policyRun().id());
        assertEquals(evaluate, prepared.policyEvaluateCommand());
        var ordered = inOrder(fixture.taskMapper, fixture.policyMapper, fixture.commandMapper);
        ordered.verify(fixture.taskMapper).lockByIdForUpdate(201L);
        ordered.verify(fixture.policyMapper).findById(101L);
        ordered.verify(fixture.policyMapper).insertIfAbsent(any());
        ordered.verify(fixture.policyMapper).findById(101L);
        ordered.verify(fixture.commandMapper).find(201L, "REQUIREMENT_DELIVERY", "POLICY_EVALUATE");
        ordered.verify(fixture.commandMapper).enqueue(any());
        ordered.verify(fixture.commandMapper).find(201L, "REQUIREMENT_DELIVERY", "POLICY_EVALUATE");
    }

    @Test
    void recordsCanonicalDecisionUnderLedgerTaskThenCommandLocksAndBindsExactApplyCommand() {
        Fixture fixture = fixture();
        RequirementStageCommand evaluate = runningEvaluateCommand(5L, 7L, "worker-1");
        RequirementStageCommand apply = applyCommand(6L, 8L);
        RequirementPolicyRun ready = planReady();
        when(fixture.policyMapper.lockForUpdate(101L)).thenReturn(policyRow(ready));
        when(fixture.taskMapper.lockByIdForUpdate(201L)).thenReturn(taskRow(5L, 7L, "PLAN_GENERATED"));
        when(fixture.commandMapper.lockByIdForUpdate(901L)).thenReturn(commandRow(evaluate));
        when(fixture.taskMapper.advanceStatusWithExpectedVersionFenced(
                any(Long.class), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(fixture.eventMapper.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(fixture.commandMapper.complete(901L, "worker-1", PostgresPersistenceSupport.toDateTime(NOW)))
                .thenReturn(commandRow(evaluate.succeeded(NOW)));
        when(fixture.commandFactory.createPendingCommand(
                "201", 6L, 8L, "REQUIREMENT_DELIVERY", "POLICY_APPLY", "project-1", "P1", "", "101", NOW))
                .thenReturn(apply);
        when(fixture.commandMapper.enqueue(any())).thenReturn(1);
        when(fixture.commandMapper.find(201L, "REQUIREMENT_DELIVERY", "POLICY_APPLY"))
                .thenReturn(commandRow(apply));
        when(fixture.policyMapper.compareAndSet(any(), eq("PLAN_READY"), eq(0L))).thenReturn(1);

        RequirementPolicyEvaluationResult result = fixture.adapter.recordEvaluation(
                evaluationCommand(), evaluate, "worker-1", NOW);

        assertEquals(apply, result.policyApplyCommand());
        assertEquals(6L, result.taskVersion());
        assertEquals(8L, result.fencingToken());
        ArgumentCaptor<RequirementStageCommandRow> applyRow = ArgumentCaptor.forClass(RequirementStageCommandRow.class);
        verify(fixture.commandMapper).enqueue(applyRow.capture());
        assertNull(applyRow.getValue().retryCheckpointId);
        assertEquals(0L, applyRow.getValue().businessGeneration);
        assertNull(applyRow.getValue().targetRetryBindingId);
        var ordered = inOrder(fixture.policyMapper, fixture.taskMapper, fixture.commandMapper,
                fixture.eventMapper, fixture.commandFactory);
        ordered.verify(fixture.policyMapper).lockForUpdate(101L);
        ordered.verify(fixture.taskMapper).lockByIdForUpdate(201L);
        ordered.verify(fixture.commandMapper).lockByIdForUpdate(901L);
        ordered.verify(fixture.taskMapper).advanceStatusWithExpectedVersionFenced(
                201L, 5L, 7L, "PLAN_GENERATED", "WAITING_POLICY",
                null, null, null, null, PostgresPersistenceSupport.toDateTime(NOW));
        ordered.verify(fixture.eventMapper).insert(any(RdTaskStatusEventRow.class));
        ordered.verify(fixture.commandMapper).complete(901L, "worker-1", PostgresPersistenceSupport.toDateTime(NOW));
        ordered.verify(fixture.commandFactory).createPendingCommand(
                "201", 6L, 8L, "REQUIREMENT_DELIVERY", "POLICY_APPLY", "project-1", "P1", "", "101", NOW);
        ordered.verify(fixture.commandMapper).enqueue(any());
        ordered.verify(fixture.commandMapper).find(201L, "REQUIREMENT_DELIVERY", "POLICY_APPLY");
        ordered.verify(fixture.policyMapper).compareAndSet(any(), eq("PLAN_READY"), eq(0L));
        ArgumentCaptor<RequirementPolicyRunRow> ledger = ArgumentCaptor.forClass(RequirementPolicyRunRow.class);
        verify(fixture.policyMapper).compareAndSet(ledger.capture(), eq("PLAN_READY"), eq(0L));
        assertEquals("POLICY_DECIDED", ledger.getValue().state);
        assertEquals(evaluationCommand().policyJson(), ledger.getValue().policyJson);
        assertEquals(evaluationCommand().policyDigest(), ledger.getValue().policyDigest);
        assertEquals("ALLOWED", ledger.getValue().policyAction);
        assertEquals(6L, ledger.getValue().boundTaskVersion);
        assertEquals(8L, ledger.getValue().boundFence);
    }

    @Test
    void replaysOnlyExactDecidedEvaluationAndRejectsStaleTaskBeforeWrites() {
        Fixture replay = fixture();
        RequirementPolicyRun decided = decided();
        RequirementStageCommand completed = runningEvaluateCommand(5L, 7L, "worker-1").succeeded(NOW);
        RequirementStageCommand apply = applyCommand(6L, 8L);
        RequirementPolicyRunRow jsonbNormalized = policyRow(decided);
        jsonbNormalized.policyJson = "{\"policyAction\": \"ALLOWED\", \"reason\": \"safe\"}";
        when(replay.policyMapper.lockForUpdate(101L)).thenReturn(jsonbNormalized);
        when(replay.taskMapper.lockByIdForUpdate(201L)).thenReturn(taskRow(6L, 8L, "WAITING_POLICY"));
        when(replay.commandMapper.lockByIdForUpdate(901L)).thenReturn(commandRow(completed));
        when(replay.commandMapper.find(201L, "REQUIREMENT_DELIVERY", "POLICY_APPLY"))
                .thenReturn(commandRow(apply));

        RequirementPolicyEvaluationResult result = replay.adapter.recordEvaluation(
                evaluationCommand(), runningEvaluateCommand(5L, 7L, "worker-1"), "worker-1", NOW);

        assertEquals(apply, result.policyApplyCommand());
        verify(replay.taskMapper, never()).advanceStatusWithExpectedVersionFenced(
                any(Long.class), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any());
        verify(replay.commandMapper, never()).complete(any(Long.class), any(), any());
        verify(replay.commandMapper, never()).enqueue(any());
        verify(replay.policyMapper, never()).compareAndSet(any(), any(), any(Long.class));

        Fixture stale = fixture();
        when(stale.policyMapper.lockForUpdate(101L)).thenReturn(policyRow(planReady()));
        when(stale.taskMapper.lockByIdForUpdate(201L)).thenReturn(taskRow(6L, 7L, "PLAN_GENERATED"));
        when(stale.commandMapper.lockByIdForUpdate(901L)).thenReturn(commandRow(
                runningEvaluateCommand(5L, 7L, "worker-1")));
        assertThrows(IllegalStateException.class, () -> stale.adapter.recordEvaluation(
                evaluationCommand(), runningEvaluateCommand(5L, 7L, "worker-1"), "worker-1", NOW));
        verify(stale.taskMapper, never()).advanceStatusWithExpectedVersionFenced(
                any(Long.class), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any());
        verify(stale.commandMapper, never()).complete(any(Long.class), any(), any());
    }

    @Test
    void rejectsReplayedPolicyApplyWhenItsDurableSchedulingIdentityNoLongerMatchesTheTask() {
        for (RequirementStageCommand invalid : List.of(
                policyApplyWithScheduling("other-project", "P1", ScheduleResourceClass.GENERIC, Set.of(ScheduleResourceClass.GENERIC)),
                policyApplyWithScheduling("project-1", "P9", ScheduleResourceClass.GENERIC, Set.of(ScheduleResourceClass.GENERIC)),
                policyApplyWithScheduling("project-1", "P1", ScheduleResourceClass.PROVIDER, Set.of(ScheduleResourceClass.PROVIDER))
        )) {
            Fixture fixture = fixture();
            RequirementStageCommand completed = runningEvaluateCommand(5L, 7L, "worker-1").succeeded(NOW);
            when(fixture.policyMapper.lockForUpdate(101L)).thenReturn(policyRow(decided()));
            when(fixture.taskMapper.lockByIdForUpdate(201L)).thenReturn(taskRow(6L, 8L, "WAITING_POLICY"));
            when(fixture.commandMapper.lockByIdForUpdate(901L)).thenReturn(commandRow(completed));
            when(fixture.commandMapper.find(201L, "REQUIREMENT_DELIVERY", "POLICY_APPLY"))
                    .thenReturn(commandRow(invalid));

            assertThrows(IllegalStateException.class, () -> fixture.adapter.recordEvaluation(
                    evaluationCommand(), runningEvaluateCommand(5L, 7L, "worker-1"), "worker-1", NOW));
            verify(fixture.taskMapper, never()).advanceStatusWithExpectedVersionFenced(
                    any(Long.class), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    void consumesApprovedResumeUnderLedgerTaskThenCommandLocksAndEnqueuesFirstRole() {
        Fixture fixture = fixture();
        RequirementStageCommand resume = runningResumeCommand(6L, 8L, "worker-1");
        RequirementPolicyRun approved = approved();
        RequirementStageCommand continuation = firstRoleCommand(7L, 9L);
        when(fixture.policyMapper.lockForUpdate(101L)).thenReturn(policyRow(approved));
        when(fixture.taskMapper.lockByIdForUpdate(201L)).thenReturn(taskRow(6L, 8L, "WAITING_APPROVAL"));
        when(fixture.commandMapper.lockByIdForUpdate(901L)).thenReturn(commandRow(resume));
        when(fixture.taskMapper.advanceStatusWithExpectedVersionFenced(
                any(Long.class), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(fixture.eventMapper.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(fixture.commandMapper.complete(901L, "worker-1", PostgresPersistenceSupport.toDateTime(NOW)))
                .thenReturn(commandRow(resume.succeeded(NOW)));
        when(fixture.commandFactory.createPendingCommand(
                "201", 7L, 9L, "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER",
                "project-1", "P1", "", "101", NOW)).thenReturn(continuation);
        when(fixture.commandMapper.enqueue(any())).thenReturn(1);
        when(fixture.commandMapper.find(201L, "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER"))
                .thenReturn(commandRow(continuation));
        when(fixture.policyMapper.compareAndSet(any(), eq("APPROVED"), eq(4L))).thenReturn(1);

        RequirementPolicyResumeResult result = fixture.adapter.consumeApproval(resume, "worker-1", NOW);

        assertEquals(continuation, result.nextCommand());
        assertEquals("901", result.completedResumeCommand().commandId());
        assertEquals(7L, result.taskVersion());
        assertEquals(9L, result.fencingToken());
        var ordered = inOrder(fixture.policyMapper, fixture.taskMapper, fixture.commandMapper,
                fixture.eventMapper, fixture.commandFactory);
        ordered.verify(fixture.policyMapper).lockForUpdate(101L);
        ordered.verify(fixture.taskMapper).lockByIdForUpdate(201L);
        ordered.verify(fixture.commandMapper).lockByIdForUpdate(901L);
        ordered.verify(fixture.taskMapper).advanceStatusWithExpectedVersionFenced(
                201L, 6L, 8L, "WAITING_APPROVAL", "EXECUTING",
                null, null, null, null, PostgresPersistenceSupport.toDateTime(NOW));
        ordered.verify(fixture.eventMapper).insert(any(RdTaskStatusEventRow.class));
        ordered.verify(fixture.commandMapper).complete(901L, "worker-1", PostgresPersistenceSupport.toDateTime(NOW));
        ordered.verify(fixture.commandFactory).createPendingCommand(
                "201", 7L, 9L, "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER",
                "project-1", "P1", "", "101", NOW);
        ordered.verify(fixture.commandMapper).enqueue(any());
        ordered.verify(fixture.commandMapper).find(201L, "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER");
        ordered.verify(fixture.policyMapper).compareAndSet(any(), eq("APPROVED"), eq(4L));
        assertAuditedHead(fixture, 6);
        ArgumentCaptor<RequirementPolicyRunRow> ledger = ArgumentCaptor.forClass(RequirementPolicyRunRow.class);
        verify(fixture.policyMapper).compareAndSet(ledger.capture(), eq("APPROVED"), eq(4L));
        assertEquals("APPLIED", ledger.getValue().state);
        assertEquals(7L, ledger.getValue().boundTaskVersion);
        assertEquals(9L, ledger.getValue().boundFence);
        assertEquals(901L, ledger.getValue().consumedByCommandId);
    }

    @Test
    void consumesAllowedPolicyApplyUnderLedgerTaskThenCommandLocksAndEnqueuesFirstRole() {
        Fixture fixture = fixture();
        RequirementStageCommand apply = runningApplyCommand(6L, 8L, "worker-1");
        RequirementStageCommand continuation = firstRoleCommand(7L, 9L);
        when(fixture.policyMapper.lockForUpdate(101L))
                .thenReturn(policyRow(decidedWithAction("ALLOWED")));
        when(fixture.taskMapper.lockByIdForUpdate(201L)).thenReturn(taskRow(6L, 8L, "WAITING_POLICY"));
        when(fixture.commandMapper.lockByIdForUpdate(902L)).thenReturn(commandRow(apply));
        when(fixture.taskMapper.advanceStatusWithExpectedVersionFenced(
                any(Long.class), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(fixture.eventMapper.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(fixture.commandMapper.complete(902L, "worker-1", PostgresPersistenceSupport.toDateTime(NOW)))
                .thenReturn(commandRow(apply.succeeded(NOW)));
        when(fixture.commandFactory.createPendingCommand(
                "201", 7L, 9L, "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER",
                "project-1", "P1", "", "101", NOW)).thenReturn(continuation);
        when(fixture.commandMapper.enqueue(any())).thenReturn(1);
        when(fixture.commandMapper.find(201L, "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER"))
                .thenReturn(commandRow(continuation));
        when(fixture.policyMapper.compareAndSet(any(), eq("POLICY_DECIDED"), eq(1L))).thenReturn(1);

        RequirementPolicyApplyResult result = fixture.adapter.consumePolicyApply(apply, "worker-1", NOW);

        assertEquals(RequirementPolicyApplyDisposition.ALLOWED, result.disposition());
        assertEquals(continuation, result.nextCommand());
        assertEquals("APPLIED", result.policyRun().state().name());
        assertEquals("902", result.policyRun().consumedByCommandId());
        var ordered = inOrder(fixture.policyMapper, fixture.taskMapper, fixture.commandMapper,
                fixture.eventMapper, fixture.commandFactory);
        ordered.verify(fixture.policyMapper).lockForUpdate(101L);
        ordered.verify(fixture.taskMapper).lockByIdForUpdate(201L);
        ordered.verify(fixture.commandMapper).lockByIdForUpdate(902L);
        ordered.verify(fixture.taskMapper).advanceStatusWithExpectedVersionFenced(
                201L, 6L, 8L, "WAITING_POLICY", "EXECUTING",
                null, null, null, null, PostgresPersistenceSupport.toDateTime(NOW));
        ordered.verify(fixture.eventMapper).insert(any(RdTaskStatusEventRow.class));
        ordered.verify(fixture.commandMapper).complete(902L, "worker-1", PostgresPersistenceSupport.toDateTime(NOW));
        ordered.verify(fixture.commandFactory).createPendingCommand(
                "201", 7L, 9L, "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER",
                "project-1", "P1", "", "101", NOW);
        ordered.verify(fixture.commandMapper).enqueue(any());
        ordered.verify(fixture.commandMapper).find(201L, "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER");
        ordered.verify(fixture.policyMapper).compareAndSet(any(), eq("POLICY_DECIDED"), eq(1L));
        assertAuditedHead(fixture, 6);
        String firstHash = fixture.audited.head("201").orElseThrow().stateHash();
        when(fixture.policyMapper.lockForUpdate(101L)).thenReturn(policyRow(result.policyRun()));
        when(fixture.taskMapper.lockByIdForUpdate(201L)).thenReturn(taskRow(7L, 9L, "EXECUTING"));
        when(fixture.commandMapper.lockByIdForUpdate(902L)).thenReturn(commandRow(apply.succeeded(NOW)));
        when(fixture.commandMapper.find(201L, "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER"))
                .thenReturn(commandRow(continuation));
        RequirementPolicyApplyResult replayed = fixture.adapter.consumePolicyApply(apply, "any-replay-owner", NOW);
        assertEquals(RequirementPolicyApplyDisposition.ALLOWED, replayed.disposition());
        assertEquals(firstHash, fixture.audited.head("201").orElseThrow().stateHash());
        assertEquals(1L, fixture.audited.head("201").orElseThrow().stateVersion());
    }

    @Test
    void consumePolicyApplyFailsClosedWhenAuditedContractRefMismatches() {
        Fixture fixture = policyApplyFixture(decidedWithAction("ALLOWED"));
        configureNonAllowedPolicyApply(fixture);
        when(fixture.commandFactory.createPendingCommand(any(), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any(Long.class)))
                .thenReturn(firstRoleCommand(7L, 9L));
        when(fixture.commandMapper.enqueue(any())).thenReturn(1);
        when(fixture.commandMapper.find(201L, "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER"))
                .thenReturn(commandRow(firstRoleCommand(7L, 9L)));
        RdRequirementTask other = RdRequirementTask.created("201", new CreateRequirementTaskCommand(
                "deliver", "P1", "repo", "owner", "repo", "main", "done", List.of("other"), false), NOW)
                .withState(RdTaskStatus.EXECUTING, "", "{}", "", "", NOW).withConcurrency(7L, 9L);
        fixture.audited.initializeIfAbsent(new AuditedTaskStateInitializer(new AuditedTaskStateCodec()).initialize(other));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                fixture.adapter.consumePolicyApply(runningApplyCommand(6L, 8L, "worker-1"), "worker-1", NOW));
        assertTrue(failure.getMessage().contains("contractRef"));
    }

    @Test
    void consumesWaitingApprovalAndDeniedPolicyAppliesWithoutRunnableContinuation() {
        Fixture waiting = policyApplyFixture(decidedWithAction("WAITING_APPROVAL"));
        configureNonAllowedPolicyApply(waiting);

        RequirementPolicyApplyResult waitingResult = waiting.adapter.consumePolicyApply(
                runningApplyCommand(6L, 8L, "worker-1"), "worker-1", NOW);

        assertEquals(RequirementPolicyApplyDisposition.WAITING_APPROVAL, waitingResult.disposition());
        assertEquals(null, waitingResult.nextCommand());
        assertEquals(RequirementPolicyRunState.WAITING_APPROVAL, waitingResult.policyRun().state());
        verify(waiting.commandFactory, never()).createPendingCommand(
                any(), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any(Long.class));
        verify(waiting.commandMapper, never()).enqueue(any());

        Fixture denied = policyApplyFixture(decidedWithAction("UNSAFE"));
        configureNonAllowedPolicyApply(denied);
        RequirementPolicyApplyResult deniedResult = denied.adapter.consumePolicyApply(
                runningApplyCommand(6L, 8L, "worker-1"), "worker-1", NOW);

        assertEquals(RequirementPolicyApplyDisposition.DENIED, deniedResult.disposition());
        assertEquals(null, deniedResult.nextCommand());
        assertEquals(RequirementPolicyRunState.DENIED, deniedResult.policyRun().state());
        assertEquals("902", deniedResult.policyRun().consumedByCommandId());
        assertTrue(waiting.audited.head("201").isEmpty());
        assertTrue(denied.audited.head("201").isEmpty());
        ArgumentCaptor<RdTaskStatusEventRow> event = ArgumentCaptor.forClass(RdTaskStatusEventRow.class);
        verify(denied.eventMapper).insert(event.capture());
        assertEquals("FAILED_NEEDS_HUMAN", event.getValue().status);
        assertEquals("UNSAFE: unsafe", event.getValue().message);
        verify(denied.commandFactory, never()).createPendingCommand(
                any(), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any(Long.class));
        verify(denied.commandMapper, never()).enqueue(any());
    }

    @Test
    void rejectsStalePolicyApplyAndReplaysOnlyExactTerminalDispositionWithoutWrites() {
        Fixture stale = policyApplyFixture(decidedWithAction("ALLOWED"));
        assertThrows(IllegalStateException.class, () -> stale.adapter.consumePolicyApply(
                runningApplyCommand(6L, 8L, "other-worker"), "worker-1", NOW));
        assertNoPolicyApplyWrites(stale);

        Fixture replay = fixture();
        RequirementPolicyRun denied = deniedAfterApply("NEED_INFO");
        RequirementStageCommand completed = runningApplyCommand(6L, 8L, "worker-1").succeeded(NOW);
        when(replay.policyMapper.lockForUpdate(101L)).thenReturn(policyRow(denied));
        when(replay.taskMapper.lockByIdForUpdate(201L)).thenReturn(taskRow(7L, 9L, "FAILED_NEEDS_HUMAN"));
        when(replay.commandMapper.lockByIdForUpdate(902L)).thenReturn(commandRow(completed));
        when(replay.commandMapper.find(201L, "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER"))
                .thenReturn(null);

        RequirementPolicyApplyResult result = replay.adapter.consumePolicyApply(
                runningApplyCommand(6L, 8L, "worker-1"), "any-owner", NOW);

        assertEquals(RequirementPolicyApplyDisposition.DENIED, result.disposition());
        assertNoPolicyApplyWrites(replay);
    }

    @Test
    void rejectsStaleLedgerTaskLeaseRoleStageOrFenceBeforeAnyResumeWrites() {
        Fixture staleLedger = consumeFixture(approved());
        RequirementPolicyRun wrongLedger = new RequirementPolicyRun(
                "101", "201", 4L, 6L, approved().planJson(), approved().planDigest(), approved().policyJson(),
                approved().policyDigest(), "WAITING_APPROVAL", RequirementPolicyRunState.WAITING_APPROVAL,
                6L, 8L, null, null, "", "", "", 0L, "", "", 0L, 3L, 1L, NOW);
        when(staleLedger.policyMapper.lockForUpdate(101L)).thenReturn(policyRow(wrongLedger));
        assertThrows(IllegalStateException.class, () -> staleLedger.adapter.consumeApproval(
                runningResumeCommand(6L, 8L, "worker-1"), "worker-1", NOW));
        assertNoResumeWrites(staleLedger);

        Fixture wrongLease = consumeFixture(approved());
        assertThrows(IllegalStateException.class, () -> wrongLease.adapter.consumeApproval(
                runningResumeCommand(6L, 8L, "other-worker"), "worker-1", NOW));
        assertNoResumeWrites(wrongLease);

        Fixture wrongRole = consumeFixture(approved());
        assertThrows(IllegalStateException.class, () -> wrongRole.adapter.consumeApproval(
                commandWith("901", "OTHER_ROLE", "APPROVAL_RESUME", 6L, 8L).claimed("worker-1", NOW + 1L, NOW),
                "worker-1", NOW));
        assertNoResumeWrites(wrongRole);

        Fixture wrongFence = consumeFixture(approved());
        assertThrows(IllegalStateException.class, () -> wrongFence.adapter.consumeApproval(
                runningResumeCommand(6L, 9L, "worker-1"), "worker-1", NOW));
        assertNoResumeWrites(wrongFence);

        Fixture staleTask = consumeFixture(approved());
        when(staleTask.taskMapper.lockByIdForUpdate(201L)).thenReturn(taskRow(7L, 8L, "WAITING_APPROVAL"));
        assertThrows(IllegalStateException.class, () -> staleTask.adapter.consumeApproval(
                runningResumeCommand(6L, 8L, "worker-1"), "worker-1", NOW));
        assertNoResumeWrites(staleTask);

        Fixture staleCommand = consumeFixture(approved());
        when(staleCommand.commandMapper.lockByIdForUpdate(901L)).thenReturn(commandRow(
                runningResumeCommand(6L, 8L, "worker-1").claimed("worker-1", NOW + 20_000L, NOW)));
        assertThrows(IllegalStateException.class, () -> staleCommand.adapter.consumeApproval(
                runningResumeCommand(6L, 8L, "worker-1"), "worker-1", NOW));
        assertNoResumeWrites(staleCommand);
    }

    @Test
    void propagatesTaskEventCompletionEnqueueAndLedgerFailuresWithoutLaterResumeWrites() {
        Fixture taskFailure = consumeFixture(approved());
        when(taskFailure.taskMapper.advanceStatusWithExpectedVersionFenced(
                any(Long.class), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> taskFailure.adapter.consumeApproval(
                runningResumeCommand(6L, 8L, "worker-1"), "worker-1", NOW));
        verify(taskFailure.eventMapper, never()).insert(any(RdTaskStatusEventRow.class));
        verify(taskFailure.commandMapper, never()).complete(any(Long.class), any(), any());

        Fixture eventFailure = consumeFixture(approved());
        when(eventFailure.taskMapper.advanceStatusWithExpectedVersionFenced(
                any(Long.class), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(eventFailure.eventMapper.insert(any(RdTaskStatusEventRow.class))).thenThrow(new IllegalStateException("event failed"));
        assertThrows(IllegalStateException.class, () -> eventFailure.adapter.consumeApproval(
                runningResumeCommand(6L, 8L, "worker-1"), "worker-1", NOW));
        verify(eventFailure.commandMapper, never()).complete(any(Long.class), any(), any());

        Fixture completionFailure = consumeFixture(approved());
        configureThroughEvent(completionFailure);
        when(completionFailure.commandMapper.complete(901L, "worker-1", PostgresPersistenceSupport.toDateTime(NOW))).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> completionFailure.adapter.consumeApproval(
                runningResumeCommand(6L, 8L, "worker-1"), "worker-1", NOW));
        verify(completionFailure.commandFactory, never()).createPendingCommand(
                any(), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any(Long.class));

        Fixture enqueueFailure = consumeFixture(approved());
        configureThroughCompletion(enqueueFailure);
        when(enqueueFailure.commandFactory.createPendingCommand(any(), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any(Long.class)))
                .thenReturn(firstRoleCommand(7L, 9L));
        when(enqueueFailure.commandMapper.enqueue(any())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> enqueueFailure.adapter.consumeApproval(
                runningResumeCommand(6L, 8L, "worker-1"), "worker-1", NOW));
        verify(enqueueFailure.commandMapper, never()).find(any(Long.class), any(), any());
        verify(enqueueFailure.policyMapper, never()).compareAndSet(any(), any(), any(Long.class));

        Fixture ledgerFailure = consumeFixture(approved());
        configureSuccessfulContinuation(ledgerFailure, firstRoleCommand(7L, 9L));
        when(ledgerFailure.policyMapper.compareAndSet(any(), eq("APPROVED"), eq(4L))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> ledgerFailure.adapter.consumeApproval(
                runningResumeCommand(6L, 8L, "worker-1"), "worker-1", NOW));
    }

    @Test
    void rejectsConflictingEffectiveContinuationAndReplaysOnlyItsExactAppliedCommandWithoutWrites() {
        Fixture conflict = consumeFixture(approved());
        RequirementStageCommand requested = firstRoleCommand(7L, 9L);
        configureSuccessfulContinuation(conflict, commandWith("903", "REQUIREMENT_REVIEWER",
                "ROLE_EXECUTION:REQUIREMENT_REVIEWER", 7L, 9L));
        when(conflict.commandFactory.createPendingCommand(any(), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any(Long.class)))
                .thenReturn(requested);
        assertThrows(IllegalStateException.class, () -> conflict.adapter.consumeApproval(
                runningResumeCommand(6L, 8L, "worker-1"), "worker-1", NOW));
        verify(conflict.policyMapper, never()).compareAndSet(any(), any(), any(Long.class));

        Fixture replay = fixture();
        RequirementPolicyRun applied = appliedAfterResumeConsumption();
        RequirementStageCommand completed = runningResumeCommand(6L, 8L, "worker-1").succeeded(NOW);
        when(replay.policyMapper.lockForUpdate(101L)).thenReturn(policyRow(applied));
        when(replay.taskMapper.lockByIdForUpdate(201L)).thenReturn(taskRow(7L, 9L, "EXECUTING"));
        when(replay.commandMapper.lockByIdForUpdate(901L)).thenReturn(commandRow(completed));
        RequirementStageCommand continuation = firstRoleCommand(7L, 9L);
        when(replay.commandMapper.find(201L, "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER"))
                .thenReturn(commandRow(continuation));

        RequirementPolicyResumeResult result = replay.adapter.consumeApproval(
                runningResumeCommand(6L, 8L, "worker-1"), "any-owner", NOW);

        assertEquals("902", result.nextCommand().commandId());
        assertNoResumeWrites(replay);

        Fixture staleReplayTask = fixture();
        when(staleReplayTask.policyMapper.lockForUpdate(101L)).thenReturn(policyRow(applied));
        when(staleReplayTask.taskMapper.lockByIdForUpdate(201L)).thenReturn(taskRow(8L, 10L, "EXECUTING"));
        when(staleReplayTask.commandMapper.lockByIdForUpdate(901L)).thenReturn(commandRow(completed));
        when(staleReplayTask.commandMapper.find(201L, "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER"))
                .thenReturn(commandRow(continuation));
        assertThrows(IllegalStateException.class, () -> staleReplayTask.adapter.consumeApproval(
                runningResumeCommand(6L, 8L, "worker-1"), "any-owner", NOW));
        assertNoResumeWrites(staleReplayTask);

        assertThrows(IllegalStateException.class, () -> replay.adapter.consumeApproval(
                commandWith("902", "REQUIREMENT_DELIVERY", "APPROVAL_RESUME", 6L, 8L).succeeded(NOW), "any-owner", NOW));
    }

    @Test
    void rejectsStaleTaskConcurrencyBeforeTaskCasEventLedgerOrCommandWrites() {
        Fixture fixture = fixture();
        fixture.task.version = 6L;

        assertThrows(IllegalStateException.class, () -> fixture.adapter.approve(command("host", "safe"), "host", NOW));

        assertNoApprovalWrites(fixture);
    }

    @Test
    void rejectsStaleFenceDigestOrTaskStateBeforeTaskCasEventLedgerOrCommandWrites() {
        Fixture staleFence = fixture();
        staleFence.task.fencingToken = 8L;
        assertThrows(IllegalStateException.class, () -> staleFence.adapter.approve(command("host", "safe"), "host", NOW));
        assertNoApprovalWrites(staleFence);

        Fixture digestMismatch = fixture();
        RequirementPolicyRun waiting = waiting();
        ApproveRequirementPolicyCommand wrongDigest = new ApproveRequirementPolicyCommand(
                "201", "101", 5L, 7L, RequirementPolicyRun.canonicalJsonDigest("{\"plan\":false}"),
                waiting.policyDigest(), "request-1", "APPROVED", "safe");
        assertThrows(IllegalStateException.class, () -> digestMismatch.adapter.approve(wrongDigest, "host", NOW));
        assertNoApprovalWrites(digestMismatch);

        Fixture wrongState = fixture();
        wrongState.task.status = "EXECUTING";
        assertThrows(IllegalStateException.class, () -> wrongState.adapter.approve(command("host", "safe"), "host", NOW));
        assertNoApprovalWrites(wrongState);
    }

    @Test
    void returnsSameRequestIdempotentlyWithoutWritesOnlyWhenStoredPayloadActorAndNoteMatch() {
        Fixture fixture = fixture();
        RequirementPolicyRun approved = approved();
        when(fixture.policyMapper.lockForUpdate(101L)).thenReturn(policyRow(approved));
        RequirementStageCommand resume = resumeCommand(6L, 8L);
        when(fixture.commandMapper.findById(901L))
                .thenReturn(commandRow(resume));

        RequirementPolicyApprovalResult result = fixture.adapter.approve(command("host", "safe"), "host", NOW);

        assertEquals(resume.commandId(), result.approvalResumeCommand().commandId());
        assertNoApprovalWrites(fixture);
    }

    @Test
    void returnsTheOriginalApprovalResultWhenTheExactRequestReplaysAfterConsumption() {
        Fixture fixture = fixture();
        RequirementPolicyRun applied = appliedAfterResumeConsumption();
        when(fixture.policyMapper.lockForUpdate(101L)).thenReturn(policyRow(applied));
        RequirementStageCommand resume = resumeCommand(6L, 8L).succeeded(NOW);
        when(fixture.commandMapper.findById(901L)).thenReturn(commandRow(resume));

        RequirementPolicyApprovalResult result = fixture.adapter.approve(command("host", "safe"), "host", NOW);

        assertEquals("901", result.approvalResumeCommand().commandId());
        assertEquals(6L, result.taskVersion());
        assertEquals(8L, result.fencingToken());
        assertNoApprovalWrites(fixture);
    }

    @Test
    void rejectsSameRequestWhenActorOrNoteDoesNotExactlyMatchWithoutWrites() {
        Fixture fixture = fixture();
        when(fixture.policyMapper.lockForUpdate(101L)).thenReturn(policyRow(approved()));

        assertThrows(IllegalStateException.class, () -> fixture.adapter.approve(command("host", "changed"), "host", NOW));

        assertNoApprovalWrites(fixture);

        Fixture actorMismatch = fixture();
        when(actorMismatch.policyMapper.lockForUpdate(101L)).thenReturn(policyRow(approved()));
        assertThrows(IllegalStateException.class, () -> actorMismatch.adapter.approve(command("host", "safe"), "other-host", NOW));
        assertNoApprovalWrites(actorMismatch);
    }

    @Test
    void rejectsIdempotentApprovalWhenTheBoundResumeCommandIdentityIsMissingOrMismatched() {
        assertRejectedResumeIdentity(null);
        assertRejectedResumeIdentity(commandWith("902", "REQUIREMENT_DELIVERY", "APPROVAL_RESUME", 6L, 8L));
        assertRejectedResumeIdentity(commandWith("901", "OTHER_ROLE", "APPROVAL_RESUME", 6L, 8L));
        assertRejectedResumeIdentity(commandWith("901", "REQUIREMENT_DELIVERY", "OTHER_STAGE", 6L, 8L));
        assertRejectedResumeIdentity(commandWith("901", "REQUIREMENT_DELIVERY", "APPROVAL_RESUME", 7L, 8L));
        assertRejectedResumeIdentity(commandWith("901", "REQUIREMENT_DELIVERY", "APPROVAL_RESUME", 6L, 9L));
        assertRejectedResumeIdentity(resumeWithPolicyRunId("102"));
    }

    @Test
    void propagatesTimelineFailureBeforeLedgerAndCommandWrites() {
        Fixture fixture = fixture();
        when(fixture.eventMapper.insert(any(RdTaskStatusEventRow.class))).thenThrow(new IllegalStateException("event failed"));
        when(fixture.taskMapper.advanceStatusWithExpectedVersionFenced(
                any(Long.class), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        assertThrows(IllegalStateException.class, () -> fixture.adapter.approve(command("host", "safe"), "host", NOW));

        verify(fixture.policyMapper, never()).compareAndSet(any(), any(), any(Long.class));
        verify(fixture.commandFactory, never()).createPendingCommand(any(), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any(Long.class));
        verify(fixture.commandMapper, never()).enqueue(any());
    }

    @Test
    void propagatesLedgerCasFailureAfterTheResumeCommandIsEnqueued() {
        Fixture fixture = fixture();
        when(fixture.eventMapper.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(fixture.taskMapper.advanceStatusWithExpectedVersionFenced(
                any(Long.class), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(fixture.policyMapper.compareAndSet(any(), any(), any(Long.class))).thenReturn(0);
        when(fixture.commandFactory.createPendingCommand(any(), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any(Long.class)))
                .thenReturn(resumeCommand(6L, 8L));
        when(fixture.commandMapper.enqueue(any())).thenReturn(1);

        assertThrows(IllegalStateException.class, () -> fixture.adapter.approve(command("host", "safe"), "host", NOW));

        verify(fixture.commandFactory).createPendingCommand(any(), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any(Long.class));
        verify(fixture.commandMapper).enqueue(any());
    }

    @Test
    void propagatesCommandEnqueueFailureBeforeLedgerCas() {
        Fixture fixture = fixture();
        when(fixture.eventMapper.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        when(fixture.taskMapper.advanceStatusWithExpectedVersionFenced(
                any(Long.class), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(fixture.policyMapper.compareAndSet(any(), any(), any(Long.class))).thenReturn(1);
        when(fixture.commandFactory.createPendingCommand(any(), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any(Long.class)))
                .thenReturn(resumeCommand(6L, 8L));
        when(fixture.commandMapper.enqueue(any())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> fixture.adapter.approve(command("host", "safe"), "host", NOW));

        verify(fixture.policyMapper, never()).compareAndSet(any(), any(), any(Long.class));
    }

    private static void assertNoApprovalWrites(Fixture fixture) {
        verify(fixture.taskMapper, never()).advanceStatusWithExpectedVersionFenced(
                any(Long.class), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.eventMapper, never()).insert(any(RdTaskStatusEventRow.class));
        verify(fixture.policyMapper, never()).compareAndSet(any(), any(), any(Long.class));
        verify(fixture.commandMapper, never()).enqueue(any());
    }

    private static void assertNoResumeWrites(Fixture fixture) {
        verify(fixture.taskMapper, never()).advanceStatusWithExpectedVersionFenced(
                any(Long.class), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.eventMapper, never()).insert(any(RdTaskStatusEventRow.class));
        verify(fixture.commandMapper, never()).complete(any(Long.class), any(), any());
        verify(fixture.commandMapper, never()).enqueue(any());
        verify(fixture.policyMapper, never()).compareAndSet(any(), any(), any(Long.class));
    }

    private static void assertNoPolicyApplyWrites(Fixture fixture) {
        verify(fixture.taskMapper, never()).advanceStatusWithExpectedVersionFenced(
                any(Long.class), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.eventMapper, never()).insert(any(RdTaskStatusEventRow.class));
        verify(fixture.commandMapper, never()).complete(any(Long.class), any(), any());
        verify(fixture.commandMapper, never()).enqueue(any());
        verify(fixture.policyMapper, never()).compareAndSet(any(), any(), any(Long.class));
    }

    private static Fixture policyApplyFixture(RequirementPolicyRun ledger) {
        Fixture fixture = fixture();
        RequirementStageCommand apply = runningApplyCommand(6L, 8L, "worker-1");
        when(fixture.policyMapper.lockForUpdate(101L)).thenReturn(policyRow(ledger));
        when(fixture.taskMapper.lockByIdForUpdate(201L)).thenReturn(taskRow(6L, 8L, "WAITING_POLICY"));
        when(fixture.commandMapper.lockByIdForUpdate(902L)).thenReturn(commandRow(apply));
        return fixture;
    }

    private static void configureNonAllowedPolicyApply(Fixture fixture) {
        when(fixture.taskMapper.advanceStatusWithExpectedVersionFenced(
                any(Long.class), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(fixture.eventMapper.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
        RequirementStageCommand completed = runningApplyCommand(6L, 8L, "worker-1").succeeded(NOW);
        when(fixture.commandMapper.complete(902L, "worker-1", PostgresPersistenceSupport.toDateTime(NOW)))
                .thenReturn(commandRow(completed));
        when(fixture.commandMapper.find(201L, "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER"))
                .thenReturn(null);
        when(fixture.policyMapper.compareAndSet(any(), eq("POLICY_DECIDED"), eq(1L))).thenReturn(1);
    }

    private static Fixture consumeFixture(RequirementPolicyRun ledger) {
        Fixture fixture = fixture();
        RequirementStageCommand resume = runningResumeCommand(6L, 8L, "worker-1");
        when(fixture.policyMapper.lockForUpdate(101L)).thenReturn(policyRow(ledger));
        when(fixture.taskMapper.lockByIdForUpdate(201L)).thenReturn(taskRow(6L, 8L, "WAITING_APPROVAL"));
        when(fixture.commandMapper.lockByIdForUpdate(901L)).thenReturn(commandRow(resume));
        return fixture;
    }

    private static void configureThroughEvent(Fixture fixture) {
        when(fixture.taskMapper.advanceStatusWithExpectedVersionFenced(
                any(Long.class), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(fixture.eventMapper.insert(any(RdTaskStatusEventRow.class))).thenReturn(1);
    }

    private static void configureThroughCompletion(Fixture fixture) {
        configureThroughEvent(fixture);
        RequirementStageCommand completed = runningResumeCommand(6L, 8L, "worker-1").succeeded(NOW);
        when(fixture.commandMapper.complete(901L, "worker-1", PostgresPersistenceSupport.toDateTime(NOW)))
                .thenReturn(commandRow(completed));
    }

    private static void configureSuccessfulContinuation(Fixture fixture, RequirementStageCommand effective) {
        configureThroughCompletion(fixture);
        when(fixture.commandFactory.createPendingCommand(any(), any(Long.class), any(Long.class), any(), any(), any(), any(), any(), any(), any(Long.class)))
                .thenReturn(firstRoleCommand(7L, 9L));
        when(fixture.commandMapper.enqueue(any())).thenReturn(1);
        when(fixture.commandMapper.find(201L, "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER"))
                .thenReturn(commandRow(effective));
    }

    private static void assertRejectedResumeIdentity(RequirementStageCommand resume) {
        Fixture fixture = fixture();
        when(fixture.policyMapper.lockForUpdate(101L)).thenReturn(policyRow(approved()));
        when(fixture.commandMapper.findById(901L)).thenReturn(resume == null ? null : commandRow(resume));

        assertThrows(IllegalStateException.class, () -> fixture.adapter.approve(command("host", "safe"), "host", NOW));

        assertNoApprovalWrites(fixture);
    }

    private static Fixture fixture() {
        RequirementPolicyRunMapper policyMapper = mock(RequirementPolicyRunMapper.class);
        RdTaskMapper taskMapper = mock(RdTaskMapper.class);
        RdTaskStatusEventMapper eventMapper = mock(RdTaskStatusEventMapper.class);
        RequirementStageCommandMapper commandMapper = mock(RequirementStageCommandMapper.class);
        RequirementStageCommandFactory commandFactory = mock(RequirementStageCommandFactory.class);
        RdTaskRow task = taskRow();
        InMemoryAuditedTaskStateStore audited = new InMemoryAuditedTaskStateStore();
        when(policyMapper.lockForUpdate(101L)).thenReturn(policyRow(waiting()));
        when(taskMapper.lockByIdForUpdate(201L)).thenReturn(task);
        return new Fixture(
                new PostgresRequirementPolicyTransactionAdapter(policyMapper, taskMapper, eventMapper,
                        commandMapper, commandFactory, new SnowflakeIdGenerator(1L, 1L, () -> NOW), audited),
                policyMapper, taskMapper, eventMapper, commandMapper, commandFactory, task, audited);
    }

    private static ApproveRequirementPolicyCommand command(String actor, String note) {
        RequirementPolicyRun run = waiting();
        return new ApproveRequirementPolicyCommand("201", "101", 5L, 7L, run.planDigest(), run.policyDigest(),
                "request-1", "APPROVED", note);
    }

    private static RequirementPolicyRun waiting() {
        String plan = "{\"plan\":true}";
        String policy = "{\"action\":\"WAITING_APPROVAL\"}";
        return new RequirementPolicyRun("101", "201", 3L, 5L, plan, RequirementPolicyRun.canonicalJsonDigest(plan),
                policy, RequirementPolicyRun.canonicalJsonDigest(policy), "WAITING_APPROVAL",
                RequirementPolicyRunState.WAITING_APPROVAL, 5L, 7L, null, null,
                "", "", "", 0L, "", "", 0L, 3L, 1L, 1L);
    }

    private static RequirementPolicyRun planReady() {
        String plan = "{\"plan\":true}";
        return new RequirementPolicyRun("101", "201", 5L, 7L, plan, RequirementPolicyRun.canonicalJsonDigest(plan),
                "", "", "", RequirementPolicyRunState.PLAN_READY, 5L, 7L,
                null, null, "", "", "", 0L, "", "", 0L, 0L, 1L, 1L);
    }

    private static RequirementPolicyRun decided() {
        RequirementPolicyRun ready = planReady();
        RecordRequirementPolicyDecisionCommand decision = evaluationCommand();
        return new RequirementPolicyRun("101", "201", 5L, 7L, ready.planJson(), ready.planDigest(),
                decision.policyJson(), decision.policyDigest(), decision.policyAction(),
                RequirementPolicyRunState.POLICY_DECIDED, 6L, 8L,
                null, null, "", "", "", 0L, "", "", 0L, 1L, 1L, NOW);
    }

    private static RequirementPolicyRun decidedWithAction(String action) {
        RequirementPolicyRun ready = planReady();
        String policy = "{\"policyAction\":\"" + action + "\",\"reason\":\""
                + action.toLowerCase(java.util.Locale.ROOT) + "\"}";
        return new RequirementPolicyRun("101", "201", 5L, 7L, ready.planJson(), ready.planDigest(),
                policy, RequirementPolicyRun.canonicalJsonDigest(policy), action,
                RequirementPolicyRunState.POLICY_DECIDED, 6L, 8L,
                null, null, "", "", "", 0L, "", "", 0L, 1L, 1L, NOW);
    }

    private static RequirementPolicyRun deniedAfterApply(String action) {
        RequirementPolicyRun decided = decidedWithAction(action);
        return new RequirementPolicyRun("101", "201", 5L, 7L, decided.planJson(), decided.planDigest(),
                decided.policyJson(), decided.policyDigest(), action, RequirementPolicyRunState.DENIED,
                7L, 9L, null, null, "", "", "", 0L, "", "902", NOW,
                2L, 1L, NOW);
    }

    private static RecordRequirementPolicyDecisionCommand evaluationCommand() {
        String policy = "{\"policyAction\":\"ALLOWED\",\"reason\":\"safe\"}";
        return new RecordRequirementPolicyDecisionCommand("101", "201", 5L, 7L,
                planReady().planDigest(), policy, RequirementPolicyRun.canonicalJsonDigest(policy), "ALLOWED");
    }

    private static RequirementPolicyRun approved() {
        RequirementPolicyRun run = waiting();
        return new RequirementPolicyRun("101", "201", run.sourceTaskVersion(), run.sourceFencingToken(), run.planJson(), run.planDigest(), run.policyJson(),
                run.policyDigest(), "WAITING_APPROVAL", RequirementPolicyRunState.APPROVED, 6L, 8L, 5L, 7L,
                "request-1", "host", "safe", NOW, "901", "", 0L, 4L, 1L, NOW);
    }

    private static RequirementPolicyRun appliedAfterResumeConsumption() {
        RequirementPolicyRun approved = approved();
        return new RequirementPolicyRun("101", "201", approved.sourceTaskVersion(), approved.sourceFencingToken(), approved.planJson(), approved.planDigest(),
                approved.policyJson(), approved.policyDigest(), "WAITING_APPROVAL", RequirementPolicyRunState.APPLIED,
                7L, 9L, 5L, 7L, "request-1", "host", "safe", NOW, "901", "901", NOW + 1L,
                5L, 1L, NOW + 1L);
    }

    private static RdTaskRow taskRow() {
        return taskRow(5L, 7L, "WAITING_APPROVAL");
    }

    private static RdTaskRow taskRow(long version, long fence, String status) {
        RdTaskRow row = new RdTaskRow();
        row.id = 201L; row.taskType = "REQUIREMENT"; row.status = status;
        row.version = version; row.fencingToken = fence; row.projectId = "project-1"; row.priority = "P1"; row.title = "title";
        row.acceptanceCriteriaJson = "[\"works\"]";
        return row;
    }

    private static RequirementStageCommand resumeCommand(long version, long fence) {
        return commandWith("901", "REQUIREMENT_DELIVERY", "APPROVAL_RESUME", version, fence);
    }

    private static RequirementStageCommand runningResumeCommand(long version, long fence, String owner) {
        return resumeCommand(version, fence).claimed(owner, NOW + 10_000L, NOW);
    }

    private static RequirementStageCommand runningEvaluateCommand(long version, long fence, String owner) {
        return commandWith("901", "REQUIREMENT_DELIVERY", "POLICY_EVALUATE", version, fence)
                .claimed(owner, NOW + 10_000L, NOW);
    }

    private static RequirementStageCommand runningApplyCommand(long version, long fence, String owner) {
        return applyCommand(version, fence).claimed(owner, NOW + 10_000L, NOW);
    }

    private static RequirementStageCommand applyCommand(long version, long fence) {
        return commandWith("902", "REQUIREMENT_DELIVERY", "POLICY_APPLY", version, fence);
    }

    private static RequirementStageCommand firstRoleCommand(long version, long fence) {
        return RequirementStageCommand.pending("902", "201", version, fence,
                "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER", 0, 3, NOW + 1_000L,
                ScheduleResourceClass.PROVIDER, Set.of(ScheduleResourceClass.PROVIDER), "project-1", "provider", "P1",
                "101", NOW);
    }

    private static RequirementStageCommand commandWith(
            String commandId, String role, String stage, long version, long fence
    ) {
        return RequirementStageCommand.pending(commandId, "201", version, fence, role, stage,
                0, 3, NOW + 1_000L, ScheduleResourceClass.GENERIC,
                Set.of(ScheduleResourceClass.GENERIC), "project-1", "provider", "P1", "101", NOW);
    }

    private static RequirementStageCommand resumeWithPolicyRunId(String policyRunId) {
        RequirementStageCommand resume = resumeCommand(6L, 8L);
        return new RequirementStageCommand(
                resume.commandId(), resume.taskId(), resume.taskVersion(), resume.fencingToken(),
                resume.role(), resume.stage(), resume.attemptNo(), resume.maxAttempts(),
                resume.deadlineEpochMillis(), resume.resourceClass(), resume.resourceRequirements(),
                resume.projectId(), resume.providerId(), resume.priorityRank(), resume.status(), resume.leaseOwner(),
                resume.leaseUntilEpochMillis(), resume.nextVisibleAtEpochMillis(), resume.lastError(),
                resume.createdAtEpochMillis(), resume.updatedAtEpochMillis(), policyRunId);
    }

    private static RequirementStageCommand policyApplyWithScheduling(
            String projectId, String priority, ScheduleResourceClass resourceClass, Set<ScheduleResourceClass> requirements
    ) {
        return RequirementStageCommand.pending("902", "201", 6L, 8L, "REQUIREMENT_DELIVERY", "POLICY_APPLY",
                0, 3, NOW + 1_000L, resourceClass, requirements, projectId, "provider", priority, "101", NOW);
    }

    private static RequirementPolicyRunRow policyRow(RequirementPolicyRun run) {
        RequirementPolicyRunRow row = new RequirementPolicyRunRow();
        row.id = 101L; row.taskId = 201L; row.sourceTaskVersion = run.sourceTaskVersion(); row.sourceFence = run.sourceFencingToken();
        row.planJson = run.planJson(); row.planDigest = run.planDigest(); row.policyJson = run.policyJson(); row.policyDigest = run.policyDigest();
        row.policyAction = run.policyAction(); row.state = run.state().name(); row.boundTaskVersion = run.boundTaskVersion(); row.boundFence = run.boundFencingToken();
        row.approvalExpectedTaskVersion = run.approvalExpectedTaskVersion(); row.approvalExpectedFence = run.approvalExpectedFencingToken();
        row.approvalRequestId = run.approvalRequestId(); row.approvedBy = run.approvedBy(); row.note = run.note();
        row.approvedAt = run.approvedAtEpochMillis() == 0L ? null : PostgresPersistenceSupport.toDateTime(run.approvedAtEpochMillis());
        row.approvalResumeCommandId = run.approvalResumeCommandId().isBlank() ? null : Long.parseLong(run.approvalResumeCommandId());
        row.consumedByCommandId = run.consumedByCommandId().isBlank() ? null : Long.parseLong(run.consumedByCommandId());
        row.consumedAt = run.consumedAtEpochMillis() == 0L ? null : PostgresPersistenceSupport.toDateTime(run.consumedAtEpochMillis());
        row.ledgerVersion = run.ledgerVersion(); row.createdAt = PostgresPersistenceSupport.toDateTime(run.createdAtEpochMillis()); row.updatedAt = PostgresPersistenceSupport.toDateTime(run.updatedAtEpochMillis());
        return row;
    }

    private static RequirementStageCommandRow commandRow(RequirementStageCommand command) {
        RequirementStageCommandRow row = new RequirementStageCommandRow();
        row.id = Long.parseLong(command.commandId()); row.taskId = Long.parseLong(command.taskId()); row.taskVersion = command.taskVersion(); row.fencingToken = command.fencingToken();
        row.role = command.role(); row.stage = command.stage();
        row.policyRunId = command.policyRunId().isBlank() ? null : Long.parseLong(command.policyRunId());
        row.attemptNo = command.attemptNo(); row.maxAttempts = command.maxAttempts();
        row.deadlineAt = PostgresPersistenceSupport.toDateTime(command.deadlineEpochMillis()); row.resourceClass = command.resourceClass().name(); row.resourceRequirements = command.encodedResourceRequirements();
        row.projectId = command.projectId(); row.providerId = command.providerId(); row.priorityRank = command.priorityRank(); row.status = command.status().name(); row.leaseOwner = command.leaseOwner();
        row.nextVisibleAt = PostgresPersistenceSupport.toDateTime(command.nextVisibleAtEpochMillis()); row.lastError = command.lastError(); row.createdAt = PostgresPersistenceSupport.toDateTime(command.createdAtEpochMillis()); row.updatedAt = PostgresPersistenceSupport.toDateTime(command.updatedAtEpochMillis());
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class PolicyTransactionClassProxyConfiguration {

        @Bean
        RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }

        @Bean
        PolicyMapperTransactionProbe policyMapperTransactionProbe() {
            return new PolicyMapperTransactionProbe();
        }

        @Bean
        RequirementPolicyRunMapper requirementPolicyRunMapper(PolicyMapperTransactionProbe probe) {
            return probe.mapper();
        }

        @Bean
        RdTaskMapper rdTaskMapper() {
            return noOpMapper(RdTaskMapper.class);
        }

        @Bean
        RdTaskStatusEventMapper rdTaskStatusEventMapper() {
            return noOpMapper(RdTaskStatusEventMapper.class);
        }

        @Bean
        RequirementStageCommandMapper requirementStageCommandMapper() {
            return noOpMapper(RequirementStageCommandMapper.class);
        }

        @Bean
        RequirementStageCommandFactory requirementStageCommandFactory() {
            return new RequirementStageCommandFactory(
                    new SnowflakeIdGenerator(1L, 1L, () -> NOW), 3,
                    RequirementDeliverySchedulingPolicy.defaults(), null);
        }

        @Bean
        PostgresRequirementPolicyTransactionAdapter postgresRequirementPolicyTransactionAdapter(
                RequirementPolicyRunMapper policyMapper,
                RdTaskMapper taskMapper,
                RdTaskStatusEventMapper eventMapper,
                RequirementStageCommandMapper commandMapper,
                RequirementStageCommandFactory commandFactory
        ) {
            return new PostgresRequirementPolicyTransactionAdapter(
                    policyMapper, taskMapper, eventMapper, commandMapper, commandFactory,
                    new SnowflakeIdGenerator(1L, 2L, () -> NOW));
        }
    }

    private static final class PolicyMapperTransactionProbe {
        private final AtomicBoolean approveSawActiveTransaction = new AtomicBoolean();
        private final AtomicBoolean consumeSawActiveTransaction = new AtomicBoolean();

        private RequirementPolicyRunMapper mapper() {
            return (RequirementPolicyRunMapper) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {RequirementPolicyRunMapper.class},
                    (proxy, method, args) -> {
                        if ("lockForUpdate".equals(method.getName())) {
                            approveSawActiveTransaction.set(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                        }
                        if ("lockForUpdate".equals(method.getName())) {
                            consumeSawActiveTransaction.set(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                        }
                        return defaultValue(method.getReturnType());
                    });
        }
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private final AtomicInteger begins = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begins.incrementAndGet();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // The two exercised methods throw after their first locked read.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks.incrementAndGet();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T noOpMapper(Class<T> mapperType) {
        return (T) Proxy.newProxyInstance(
                PostgresRequirementPolicyTransactionAdapterTest.class.getClassLoader(),
                new Class<?>[] {mapperType},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private static void assertAuditedHead(Fixture fixture, int recordCount) {
        AuditedTaskState head = fixture.audited.head("201").orElseThrow();
        assertEquals(1L, head.stateVersion());
        assertEquals(recordCount, head.records().size());
    }

    private record Fixture(PostgresRequirementPolicyTransactionAdapter adapter, RequirementPolicyRunMapper policyMapper,
                           RdTaskMapper taskMapper, RdTaskStatusEventMapper eventMapper, RequirementStageCommandMapper commandMapper,
                           RequirementStageCommandFactory commandFactory, RdTaskRow task,
                           InMemoryAuditedTaskStateStore audited) { }
}
