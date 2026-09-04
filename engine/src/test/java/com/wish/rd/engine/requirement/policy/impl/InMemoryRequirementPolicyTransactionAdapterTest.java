package com.wish.rd.engine.requirement.policy.impl;

import com.wish.rd.engine.requirement.audit.AuditedTaskState;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateInitializer;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateCodec;
import com.wish.rd.engine.requirement.audit.impl.InMemoryAuditedTaskStateStore;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.policy.model.ApproveRequirementPolicyCommand;
import com.wish.rd.engine.requirement.policy.model.RecordRequirementPolicyDecisionCommand;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApplyDisposition;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRunState;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRetryContext;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationProposal;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.scheduling.FairRequirementDeliveryClaimPlanner;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the bounded, same-JVM policy transaction compatibility path. */
class InMemoryRequirementPolicyTransactionAdapterTest {
    private static final long NOW = 1_784_100_000_000L;

    @Test
    void preparesPlanReadyLedgerAndExactEvaluateCommandBeforeEitherBecomesVisible() {
        AtomicLong clock = new AtomicLong(NOW);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, clock::getAndIncrement);
        InMemoryRdTaskStore tasks = new InMemoryRdTaskStore();
        InMemoryRequirementPolicyRunStore runs = new InMemoryRequirementPolicyRunStore();
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        RdRequirementTask task = RdRequirementTask.created("prepare-task", new CreateRequirementTaskCommand(
                "deliver", "P1", "repo", "owner", "repo", "main", "done", List.of("works"), false), NOW)
                .withState(RdTaskStatus.PLAN_GENERATED, "", "{}", "", "", NOW).withConcurrency(5L, 7L);
        tasks.saveRequirementTask(task);
        InMemoryRequirementPolicyTransactionAdapter adapter = new InMemoryRequirementPolicyTransactionAdapter(
                runs, tasks, events, commands, ids);
        RequirementStageCommand evaluate = pending("prepare-evaluate", "prepare-task", 5L, 7L,
                "REQUIREMENT_DELIVERY", "POLICY_EVALUATE", "prepare-evaluate", NOW);
        String plan = "{\"plan\":true}";
        String policy = "{\"policyAction\":\"ALLOWED\",\"reason\":\"safe\"}";

        var prepared = adapter.prepareEvaluation(new com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationProposal(
                "prepare-task", 5L, 7L, plan, RequirementPolicyRun.canonicalJsonDigest(plan),
                policy, RequirementPolicyRun.canonicalJsonDigest(policy), "ALLOWED"), evaluate, NOW);

        assertEquals("prepare-evaluate", prepared.policyRun().id());
        assertEquals(RequirementPolicyRunState.PLAN_READY, prepared.policyRun().state());
        assertEquals(evaluate, prepared.policyEvaluateCommand());
        assertEquals(evaluate, commands.findById("prepare-evaluate").orElseThrow());
    }

    @Test
    void shouldEvaluateAllowedPolicyApplyAndReplayTheExactFirstRole() {
        Fixture fixture = fixture("ALLOWED");

        var evaluated = fixture.adapter.recordEvaluation(fixture.decision, claim(fixture.commands, fixture.evaluation, "worker"), "worker", NOW);
        RequirementStageCommand apply = claim(fixture.commands, evaluated.policyApplyCommand(), "worker");
        var applied = fixture.adapter.consumePolicyApply(apply, "worker", NOW + 1L);

        assertEquals(RequirementPolicyApplyDisposition.ALLOWED, applied.disposition());
        assertEquals(RdTaskStatus.EXECUTING, fixture.tasks.findRequirementTask("task-1").orElseThrow().status());
        assertEquals("REQUIREMENT_REVIEWER", applied.nextCommand().role());
        assertEquals("run-1", applied.nextCommand().policyRunId());
        assertEquals(applied, fixture.adapter.consumePolicyApply(apply, "any-replay-owner", NOW + 2L));
        AuditedTaskState head = fixture.audited.head("task-1").orElseThrow();
        assertEquals(1L, head.stateVersion());
        assertEquals(6, head.records().size());
        assertEquals(head.stateHash(), fixture.audited.initializeIfAbsent(
                new AuditedTaskStateInitializer(new AuditedTaskStateCodec()).initialize(
                        fixture.tasks.findRequirementTask("task-1").orElseThrow())).stateHash());
    }

    @Test
    void shouldApplyWaitingApprovalThenApproveResumeAndReplay() {
        Fixture fixture = fixture("WAITING_APPROVAL");
        var evaluated = fixture.adapter.recordEvaluation(fixture.decision, claim(fixture.commands, fixture.evaluation, "worker"), "worker", NOW);
        var waiting = fixture.adapter.consumePolicyApply(claim(fixture.commands, evaluated.policyApplyCommand(), "worker"), "worker", NOW + 1L);
        assertTrue(fixture.audited.head("task-1").isEmpty());
        ApproveRequirementPolicyCommand approval = new ApproveRequirementPolicyCommand(
                "task-1", "run-1", waiting.taskVersion(), waiting.fencingToken(), fixture.planDigest,
                fixture.policyDigest, "approval-1", "APPROVED", "approved locally");

        var approved = fixture.adapter.approve(approval, "host", NOW + 2L);
        RequirementStageCommand resume = claim(fixture.commands, approved.approvalResumeCommand(), "worker");
        var resumed = fixture.adapter.consumeApproval(resume, "worker", NOW + 3L);

        assertEquals(RdTaskStatus.EXECUTING, fixture.tasks.findRequirementTask("task-1").orElseThrow().status());
        assertEquals("run-1", resumed.nextCommand().policyRunId());
        assertEquals(resumed, fixture.adapter.consumeApproval(resume, "other-replay-owner", NOW + 4L));
        assertEquals(1L, fixture.audited.head("task-1").orElseThrow().stateVersion());
        assertEquals(6, fixture.audited.head("task-1").orElseThrow().records().size());
    }

    @Test
    void shouldFailClosedWhenAuditedContractRefMismatchesOnAllow() {
        Fixture fixture = fixture("ALLOWED");
        RdRequirementTask other = RdRequirementTask.created("task-1", new CreateRequirementTaskCommand(
                "deliver", "P1", "repo", "owner", "repo", "main", "done", List.of("other"), false), NOW)
                .withState(RdTaskStatus.PLAN_GENERATED, "", "{}", "", "", NOW).withConcurrency(5L, 7L);
        fixture.audited.initializeIfAbsent(new AuditedTaskStateInitializer(new AuditedTaskStateCodec()).initialize(other));
        var evaluated = fixture.adapter.recordEvaluation(fixture.decision, claim(fixture.commands, fixture.evaluation, "worker"), "worker", NOW);
        RequirementStageCommand apply = claim(fixture.commands, evaluated.policyApplyCommand(), "worker");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> fixture.adapter.consumePolicyApply(apply, "worker", NOW + 1L));
        assertTrue(failure.getMessage().contains("contractRef"));
    }

    @Test
    void shouldFailClosedForMissingOrWrongPolicyLedgerReference() {
        Fixture fixture = fixture("ALLOWED");
        RequirementStageCommand missing = withPolicyRunId(fixture.evaluation, "missing-run");
        assertThrows(IllegalStateException.class, () -> fixture.adapter.recordEvaluation(
                fixture.decision, missing.claimed("worker", NOW + 60_000L, NOW), "worker", NOW));

        RequirementStageCommand leased = withPolicyRunId(fixture.evaluation, "other-run").claimed("worker", NOW + 60_000L, NOW);
        assertThrows(IllegalStateException.class, () -> fixture.adapter.recordEvaluation(
                fixture.decision, leased, "worker", NOW));
    }

    @Test
    void supersedesOnlyTheExactActivePolicySourceForACheckpointBoundPolicyRetry() {
        Fixture fixture = fixture("ALLOWED");
        RequirementPolicyRetryContext context = new RequirementPolicyRetryContext(
                "101", 101L, "binding-reviewer", "run-1", fixture.planDigest,
                TaskRetryCheckpointStatus.DISPATCHED);

        RequirementPolicyRun superseded = fixture.adapter.supersedeForPolicyRetry(context, 0L, NOW + 1L);

        assertEquals(RequirementPolicyRunState.SUPERSEDED, superseded.state());
        assertThrows(IllegalStateException.class, () -> fixture.adapter.supersedeForPolicyRetry(
                context, superseded.ledgerVersion(), NOW + 2L));
    }

    @Test
    void propagatesOneExplicitRetryContextThroughEveryPolicyTransactionOperation() {
        AtomicLong clock = new AtomicLong(NOW);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, clock::getAndIncrement);
        InMemoryRdTaskStore tasks = new InMemoryRdTaskStore();
        InMemoryRequirementPolicyRunStore runs = new InMemoryRequirementPolicyRunStore();
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RdRequirementTask task = RdRequirementTask.created("retry-policy-task", new CreateRequirementTaskCommand(
                "deliver", "P1", "repo", "owner", "repo", "main", "done", List.of("works"), false), NOW)
                .withState(RdTaskStatus.PLAN_GENERATED, "", "{}", "", "", NOW).withConcurrency(5L, 7L);
        tasks.saveRequirementTask(task);
        InMemoryRequirementPolicyTransactionAdapter adapter = new InMemoryRequirementPolicyTransactionAdapter(
                runs, tasks, new InMemoryRdTaskStatusEventStore(), commands, ids,
                new InMemoryAuditedTaskStateStore());
        String plan = "{\"plan\":true}";
        String policy = "{\"policyAction\":\"WAITING_APPROVAL\",\"reason\":\"review\"}";
        String planDigest = RequirementPolicyRun.canonicalJsonDigest(plan);
        String policyDigest = RequirementPolicyRun.canonicalJsonDigest(policy);
        RequirementPolicyRetryContext context = new RequirementPolicyRetryContext(
                "101", 101L, "binding-reviewer", "superseded-policy-run", planDigest,
                TaskRetryCheckpointStatus.DISPATCHED);
        RequirementStageCommand evaluate = pendingRetry("retry-evaluate", "retry-policy-task", 5L, 7L,
                "REQUIREMENT_DELIVERY", "POLICY_EVALUATE", "retry-evaluate", context, "", NOW);
        RequirementPolicyEvaluationProposal proposal = new RequirementPolicyEvaluationProposal(
                "retry-policy-task", 5L, 7L, plan, planDigest, policy, policyDigest, "WAITING_APPROVAL", context);

        var prepared = adapter.prepareEvaluation(proposal, evaluate, context, NOW);
        RecordRequirementPolicyDecisionCommand decision = new RecordRequirementPolicyDecisionCommand(
                "retry-evaluate", "retry-policy-task", 5L, 7L, planDigest, policy, policyDigest,
                "WAITING_APPROVAL", context);
        var evaluated = adapter.recordEvaluation(decision, claim(commands, prepared.policyEvaluateCommand(), "worker"), context, "worker", NOW + 1L);
        var waiting = adapter.consumePolicyApply(claim(commands, evaluated.policyApplyCommand(), "worker"), context, "worker", NOW + 2L);
        ApproveRequirementPolicyCommand approval = new ApproveRequirementPolicyCommand(
                "retry-policy-task", "retry-evaluate", waiting.taskVersion(), waiting.fencingToken(), planDigest,
                policyDigest, "retry-approval", "APPROVED", "approved", context);
        var approved = adapter.approve(approval, context, "host", NOW + 3L);
        var resumed = adapter.consumeApproval(claim(commands, approved.approvalResumeCommand(), "worker"), context,
                "worker", NOW + 4L);

        assertEquals(context, prepared.retryContext());
        assertEquals(context, evaluated.retryContext());
        assertEquals(context, waiting.retryContext());
        assertEquals(context, approved.retryContext());
        assertEquals(context, resumed.retryContext());
        assertEquals("binding-reviewer", resumed.nextCommand().targetRetryBindingId());
    }

    @Test
    void legacyOverloadsRejectEmbeddedRetryContext() {
        AtomicLong clock = new AtomicLong(NOW);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, clock::getAndIncrement);
        InMemoryRdTaskStore tasks = new InMemoryRdTaskStore();
        InMemoryRequirementPolicyRunStore runs = new InMemoryRequirementPolicyRunStore();
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RdRequirementTask task = RdRequirementTask.created("legacy-context-task", new CreateRequirementTaskCommand(
                "deliver", "P1", "repo", "owner", "repo", "main", "done", List.of("works"), false), NOW)
                .withState(RdTaskStatus.PLAN_GENERATED, "", "{}", "", "", NOW).withConcurrency(5L, 7L);
        tasks.saveRequirementTask(task);
        InMemoryRequirementPolicyTransactionAdapter adapter = new InMemoryRequirementPolicyTransactionAdapter(
                runs, tasks, new InMemoryRdTaskStatusEventStore(), commands, ids,
                new InMemoryAuditedTaskStateStore());
        String plan = "{\"plan\":true}";
        String digest = RequirementPolicyRun.canonicalJsonDigest(plan);
        String policy = "{\"policyAction\":\"ALLOWED\",\"reason\":\"safe\"}";
        String policyDigest = RequirementPolicyRun.canonicalJsonDigest(policy);
        RequirementPolicyRetryContext context = new RequirementPolicyRetryContext(
                "101", 101L, "binding-reviewer", "source", digest, TaskRetryCheckpointStatus.DISPATCHED);
        RequirementPolicyEvaluationProposal proposal = new RequirementPolicyEvaluationProposal(
                "legacy-context-task", 5L, 7L, plan, digest, policy, policyDigest, "ALLOWED", context);
        RequirementStageCommand evaluate = pendingRetry("legacy-evaluate", "legacy-context-task", 5L, 7L,
                "REQUIREMENT_DELIVERY", "POLICY_EVALUATE", "legacy-evaluate", context, "", NOW);

        assertThrows(IllegalArgumentException.class, () -> adapter.prepareEvaluation(proposal, evaluate, NOW));
    }

    private static Fixture fixture(String action) {
        AtomicLong clock = new AtomicLong(NOW);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, clock::getAndIncrement);
        InMemoryRdTaskStore tasks = new InMemoryRdTaskStore();
        InMemoryRequirementPolicyRunStore runs = new InMemoryRequirementPolicyRunStore();
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        RdRequirementTask task = RdRequirementTask.created("task-1", new CreateRequirementTaskCommand(
                "deliver", "P1", "repo", "owner", "repo", "main", "done", List.of("works"), false), NOW)
                .withState(RdTaskStatus.PLAN_GENERATED, "", "{}", "", "", NOW).withConcurrency(5L, 7L);
        tasks.saveRequirementTask(task);
        String planJson = "{\"plan\":true}";
        String policyJson = "{\"policyAction\":\"" + action + "\",\"reason\":\"safe\"}";
        String planDigest = RequirementPolicyRun.canonicalJsonDigest(planJson);
        String policyDigest = RequirementPolicyRun.canonicalJsonDigest(policyJson);
        RequirementPolicyRun run = new RequirementPolicyRun("run-1", "task-1", 5L, 7L, planJson, planDigest,
                "", "", "", RequirementPolicyRunState.PLAN_READY, 5L, 7L, null, null, "", "", "", 0L,
                "", "", 0L, 0L, NOW, NOW);
        runs.createOrGet(run);
        RequirementStageCommand evaluation = pending("eval-1", "task-1", 5L, 7L,
                "REQUIREMENT_DELIVERY", "POLICY_EVALUATE", "run-1", NOW);
        commands.enqueue(evaluation);
        RecordRequirementPolicyDecisionCommand decision = new RecordRequirementPolicyDecisionCommand(
                "run-1", "task-1", 5L, 7L, planDigest, policyJson, policyDigest, action);
        InMemoryAuditedTaskStateStore audited = new InMemoryAuditedTaskStateStore();
        return new Fixture(new InMemoryRequirementPolicyTransactionAdapter(runs, tasks, events, commands, ids, audited),
                tasks, commands, audited, evaluation, decision, planDigest, policyDigest);
    }

    private static RequirementStageCommand claim(InMemoryRequirementStageCommandStore store, RequirementStageCommand command, String owner) {
        long now = Math.max(NOW, command.nextVisibleAtEpochMillis());
        return store.claim(command.commandId(), owner, now, 60_000L).orElseThrow();
    }

    private static RequirementStageCommand pending(String id, String task, long version, long fence,
            String role, String stage, String policyRunId, long now) {
        var requirements = FairRequirementDeliveryClaimPlanner.classifyStageRequirements(role, stage);
        ScheduleResourceClass resource = requirements.stream().filter(value -> value != ScheduleResourceClass.GENERIC)
                .findFirst().orElse(ScheduleResourceClass.GENERIC);
        return RequirementStageCommand.pending(id, task, version, fence, role, stage, 0, 3, now + 300_000L,
                resource, requirements, "project", "memory", "P1", policyRunId, now);
    }

    private static RequirementStageCommand pendingRetry(String id, String task, long version, long fence,
            String role, String stage, String policyRunId, RequirementPolicyRetryContext context,
            String targetBindingId, long now) {
        var requirements = FairRequirementDeliveryClaimPlanner.classifyStageRequirements(role, stage);
        ScheduleResourceClass resource = requirements.stream().filter(value -> value != ScheduleResourceClass.GENERIC)
                .findFirst().orElse(ScheduleResourceClass.GENERIC);
        return RequirementStageCommand.pending(id, task, version, fence, role, stage, 0, 3, now + 300_000L,
                resource, requirements, "project", "memory", "P1", policyRunId, context.checkpointId(),
                context.businessGeneration(), targetBindingId, now);
    }

    private static RequirementStageCommand withPolicyRunId(RequirementStageCommand command, String policyRunId) {
        return new RequirementStageCommand(command.commandId() + "-other", command.taskId(), command.taskVersion(),
                command.fencingToken(), command.role(), command.stage(), command.attemptNo(), command.maxAttempts(),
                command.deadlineEpochMillis(), command.resourceClass(), command.resourceRequirements(), command.projectId(),
                command.providerId(), command.priorityRank(), command.status(), command.leaseOwner(),
                command.leaseUntilEpochMillis(), command.nextVisibleAtEpochMillis(), command.lastError(),
                command.createdAtEpochMillis(), command.updatedAtEpochMillis(), policyRunId);
    }

    private record Fixture(
            InMemoryRequirementPolicyTransactionAdapter adapter,
            InMemoryRdTaskStore tasks,
            InMemoryRequirementStageCommandStore commands,
            InMemoryAuditedTaskStateStore audited,
            RequirementStageCommand evaluation,
            RecordRequirementPolicyDecisionCommand decision,
            String planDigest,
            String policyDigest
    ) { }
}
