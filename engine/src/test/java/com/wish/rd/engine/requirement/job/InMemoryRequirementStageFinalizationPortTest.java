package com.wish.rd.engine.requirement.job;

import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageFinalizationPort;
import com.wish.rd.engine.requirement.job.model.CommandDisposition;
import com.wish.rd.engine.requirement.job.model.ContinuationSpec;
import com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJob;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.requirement.job.model.RequirementStageFinalization;
import com.wish.rd.engine.requirement.job.model.RequirementTaskMutation;
import com.wish.rd.engine.requirement.publication.RequirementPublicationLedger;
import com.wish.rd.engine.requirement.publication.RequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.impl.InMemoryRequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.model.RequirementPublication;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationPrepareCommand;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationStatus;
import com.wish.rd.engine.requirement.policy.impl.InMemoryRequirementPolicyRunStore;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRunState;
import com.wish.rd.engine.requirement.publication.RequirementOperationId;
import com.wish.rd.engine.retry.TaskFailureDiagnosticParser;
import com.wish.rd.engine.retry.TaskFailureRecoveryService;
import com.wish.rd.engine.retry.TaskRetryExhaustionProvenanceFactory;
import com.wish.rd.engine.retry.TaskRetryPointResolver;
import com.wish.rd.engine.retry.TaskRetryRoutePlanner;
import com.wish.rd.engine.retry.model.TaskFailureRecoverySnapshot;
import com.wish.rd.engine.retry.model.TaskRetryRoute;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryAttemptBindingStore;
import com.wish.rd.engine.requirement.review.impl.InMemoryAiReviewRunStore;
import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryCheckpointStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryFailureProvenanceStore;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryAttemptBinding;
import com.wish.rd.engine.retry.model.TaskRetryAttemptKind;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskRetryFailureProvenance;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.engine.scheduling.model.RequirementDeliverySchedulingPolicy;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.impl.CoordinatedRdTaskStatePersistence;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class InMemoryRequirementStageFinalizationPortTest {

    @Test
    void policyEvaluateContinuationCreatesPlanReadyLedgerBeforeCommandIsExposed() {
        long now = 1_784_800_000_000L;
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        taskStore.saveRequirementTask(RdRequirementTask.created("task-policy", new CreateRequirementTaskCommand(
                "Task title", "P1", "https://example.invalid/repo.git", "owner", "repo", "main",
                "deliver", List.of("criterion"), false), now)
                .withState(RdTaskStatus.PLAN_GENERATING, "", "", "", "", now)
                .withConcurrency(4L, 9L));
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand pending = RequirementStageCommand.pending(
                "command-policy", "task-policy", 4L, 9L, "REQUIREMENT_DELIVERY", "PLAN_GENERATING",
                0, 3, now + 60_000L, ScheduleResourceClass.GENERIC, "project-1", "", "P1", now);
        commands.enqueue(pending);
        RequirementStageCommand claimed = commands.claim(pending.commandId(), "worker-1", now, 30_000L)
                .orElseThrow();
        InMemoryRequirementPolicyRunStore policyRuns = new InMemoryRequirementPolicyRunStore();
        InMemoryRequirementStageFinalizationPort finalizer = new InMemoryRequirementStageFinalizationPort(
                commands, new InMemoryRequirementDeliveryJobStore(), taskStore,
                new CoordinatedRdTaskStatePersistence(taskStore, events),
                new SnowflakeIdGenerator(1, 1, () -> now), null, policyRuns);
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                claimed.taskId(), claimed.taskVersion(), claimed.fencingToken(), RdTaskStatus.PLAN_GENERATING,
                List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.PLAN_GENERATING, RdTaskStatus.PLAN_GENERATED,
                        "", "{\"taskId\":\"task-policy\"}", "", "", "")),
                CommandDisposition.SUCCEEDED,
                new ContinuationSpec("REQUIREMENT_DELIVERY", "POLICY_EVALUATE"),
                ExternalEffectReceipt.none());
        RequirementStageCommand continuation = RequirementStageCommand.pending(
                "policy-run-1", "task-policy", plan.postVersion(), plan.postFencingToken(),
                "REQUIREMENT_DELIVERY", "POLICY_EVALUATE", 0, 3, now + 60_000L,
                ScheduleResourceClass.GENERIC, java.util.Set.of(ScheduleResourceClass.GENERIC),
                "project-1", "", "P1", "policy-run-1", now + 1L);
        RequirementStageFinalization marker = finalizer.recordOutcome(
                finalizer.prepare(claimed, "worker-1", RdTaskStatus.PLAN_GENERATING, now),
                claimed, "worker-1", plan, now + 1L);

        RequirementStageFinalizationPort.FinalizationResult result = finalizer.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        marker, claimed, "worker-1", plan, continuation, null,
                        RequirementStageFinalizationPort.JobDisposition.NONE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, now + 2L));

        assertEquals(continuation.commandId(), result.nextCommand().commandId());
        assertEquals("PLAN_READY", policyRuns.findById(continuation.policyRunId()).orElseThrow().state().name());
        assertEquals(plan.postVersion(), policyRuns.findById(continuation.policyRunId()).orElseThrow().sourceTaskVersion());
        assertEquals(plan.postFencingToken(), policyRuns.findById(continuation.policyRunId()).orElseThrow().sourceFencingToken());
    }

    @Test
    void appliesOrderedPlanMutationsWithVersionFenceAndTimelineEvents() {
        long now = 1_784_800_000_000L;
        AtomicLong eventClock = new AtomicLong(now);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        taskStore.saveRequirementTask(RdRequirementTask.created("task-1", new CreateRequirementTaskCommand(
                "Task title", "P1", "https://example.invalid/repo.git", "owner", "repo", "main",
                "deliver", List.of("criterion"), false), now));
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand pending = RequirementStageCommand.pending(
                "command-1", "task-1", 0L, 1L, "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING",
                0, 3, now + 60_000L, ScheduleResourceClass.GENERIC, "project-1", "", "P1", now);
        commands.enqueue(pending);
        RequirementStageCommand claimed = commands.claim(pending.commandId(), "worker-1", now, 30_000L)
                .orElseThrow();
        InMemoryRequirementStageFinalizationPort finalizer = new InMemoryRequirementStageFinalizationPort(
                commands,
                new InMemoryRequirementDeliveryJobStore(),
                taskStore,
                new CoordinatedRdTaskStatePersistence(taskStore, events),
                new SnowflakeIdGenerator(1, 1, eventClock::getAndIncrement)
        );
        RequirementStageFinalization prepared = finalizer.prepare(
                claimed, "worker-1", RdTaskStatus.CREATED, now);
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                "task-1", 0L, 1L, RdTaskStatus.CREATED,
                List.of(
                        RequirementTaskMutation.statusTransition(
                                RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING,
                                "collect prompt", "{\"first\":true}", "", "", "materials collected"),
                        RequirementTaskMutation.statusTransition(
                                RdTaskStatus.MATERIAL_COLLECTING, RdTaskStatus.MATERIAL_READY,
                                "", "", "https://example.invalid/pr/1", "", "materials ready")
                ),
                CommandDisposition.SUCCEEDED,
                ContinuationSpec.terminal(),
                ExternalEffectReceipt.none()
        );
        RequirementStageFinalization recorded = finalizer.recordOutcome(
                prepared, claimed, "worker-1", plan, now + 1L);

        finalizer.finalize(new RequirementStageFinalizationPort.FinalizationCommand(
                recorded, claimed, "worker-1", plan, null, null,
                RequirementStageFinalizationPort.JobDisposition.NONE,
                RequirementStageFinalizationPort.TaskMutationDisposition.APPLY,
                now + 2L));

        RdRequirementTask task = taskStore.findRequirementTask("task-1").orElseThrow();
        assertEquals(RdTaskStatus.MATERIAL_READY, task.status());
        assertEquals(2L, task.version());
        assertEquals(3L, task.fencingToken());
        assertEquals("collect prompt", task.promptSnapshot());
        assertEquals("{\"first\":true}", task.executionResultJson());
        assertEquals("https://example.invalid/pr/1", task.pullRequestUrl());
        assertEquals(List.of(RdTaskStatus.MATERIAL_COLLECTING.name(), RdTaskStatus.MATERIAL_READY.name()),
                events.listByTask("task-1").stream().map(event -> event.status()).toList());
        assertEquals(List.of("materials collected", "materials ready"),
                events.listByTask("task-1").stream().map(event -> event.message()).toList());
    }

    @Test
    void rejectsAStaleTaskSnapshotBeforeCompletingTheStageCommand() {
        long now = 1_784_800_100_000L;
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        taskStore.saveRequirementTask(RdRequirementTask.created("task-stale", new CreateRequirementTaskCommand(
                "Task title", "P1", "https://example.invalid/repo.git", "owner", "repo", "main",
                "deliver", List.of("criterion"), false), now));
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand pending = RequirementStageCommand.pending(
                "command-stale", "task-stale", 0L, 1L, "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING",
                0, 3, now + 60_000L, ScheduleResourceClass.GENERIC, "project-1", "", "P1", now);
        commands.enqueue(pending);
        RequirementStageCommand claimed = commands.claim(pending.commandId(), "worker-1", now, 30_000L)
                .orElseThrow();
        InMemoryRequirementStageFinalizationPort finalizer = new InMemoryRequirementStageFinalizationPort(
                commands,
                new InMemoryRequirementDeliveryJobStore(),
                taskStore,
                new CoordinatedRdTaskStatePersistence(taskStore, events),
                new SnowflakeIdGenerator(1, 1, () -> now)
        );
        RequirementStageFinalization prepared = finalizer.prepare(
                claimed, "worker-1", RdTaskStatus.CREATED, now);
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                "task-stale", 0L, 1L, RdTaskStatus.CREATED,
                List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING, "", "", "", "", "")),
                CommandDisposition.SUCCEEDED,
                ContinuationSpec.terminal(),
                ExternalEffectReceipt.none()
        );
        RequirementStageFinalization recorded = finalizer.recordOutcome(
                prepared, claimed, "worker-1", plan, now + 1L);
        taskStore.advanceStatusWithExpectedVersion(
                "task-stale", 0L, 1L, RdTaskStatus.CREATED, RdTaskStatus.CREATED,
                "", null, null, null);

        assertThrows(IllegalStateException.class, () -> finalizer.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        recorded, claimed, "worker-1", plan, null, null,
                        RequirementStageFinalizationPort.JobDisposition.NONE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY,
                        now + 2L)));

        assertEquals(RequirementStageCommand.Status.RUNNING,
                commands.findById("command-stale").orElseThrow().status());
        assertEquals(RdTaskStatus.CREATED, taskStore.findRequirementTask("task-stale").orElseThrow().status());
        assertEquals(1L, taskStore.findRequirementTask("task-stale").orElseThrow().version());
        assertEquals(2L, taskStore.findRequirementTask("task-stale").orElseThrow().fencingToken());
        assertEquals(List.of(), events.listByTask("task-stale"));
    }

    @Test
    void rejectsPreparedMarkersAndPlansThatDoNotExactlyMatchTheRecordedOutcome() {
        long now = 1_784_800_150_000L;
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand pending = RequirementStageCommand.pending(
                "command-bound", "task-bound", 0L, 1L, "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING",
                0, 3, now + 60_000L, ScheduleResourceClass.GENERIC, "project-1", "", "P1", now);
        commands.enqueue(pending);
        RequirementStageCommand claimed = commands.claim(pending.commandId(), "worker-1", now, 30_000L)
                .orElseThrow();
        InMemoryRequirementStageFinalizationPort finalizer = new InMemoryRequirementStageFinalizationPort(
                commands, new InMemoryRequirementDeliveryJobStore());
        RequirementStageFinalization prepared = finalizer.prepare(claimed, "worker-1", RdTaskStatus.CREATED, now);
        RequirementStageExecutionPlan recordedPlan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION, "task-bound", 0L, 1L,
                RdTaskStatus.CREATED, List.of(), CommandDisposition.SUCCEEDED,
                ContinuationSpec.terminal(), ExternalEffectReceipt.none());

        assertThrows(IllegalStateException.class, () -> finalizer.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        prepared, claimed, "worker-1", recordedPlan, null, null,
                        RequirementStageFinalizationPort.JobDisposition.NONE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, now + 1L)));

        RequirementStageFinalization recorded = finalizer.recordOutcome(
                prepared, claimed, "worker-1", recordedPlan, now + 1L);
        RequirementStageExecutionPlan replacementPlan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION, "task-bound", 0L, 1L,
                RdTaskStatus.CREATED, List.of(), CommandDisposition.SUCCEEDED,
                new ContinuationSpec("REQUIREMENT_DELIVERY", "MATERIAL_READY"), ExternalEffectReceipt.none());

        assertThrows(IllegalStateException.class, () -> finalizer.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        recorded, claimed, "worker-1", replacementPlan, null, null,
                        RequirementStageFinalizationPort.JobDisposition.NONE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, now + 2L)));
        assertEquals(RequirementStageCommand.Status.RUNNING,
                commands.findById(claimed.commandId()).orElseThrow().status());
    }

    @Test
    void reclaimsAfterPublicationCommitFailureWithoutRepeatingTheTaskEvent() {
        RecoveryFixture fixture = RecoveryFixture.create();
        fixture.failPublicationOnce();

        assertThrows(IllegalStateException.class, fixture::finalizeApply);
        fixture.assertTaskAppliedOnceAndCommandRunning();

        fixture.reclaimAndFinalizeAlreadyApplied();

        fixture.assertRecoveredExactlyOnce();
    }

    @Test
    void reclaimsAfterJobDispositionFailureWithoutRepeatingTaskOrPublication() {
        RecoveryFixture fixture = RecoveryFixture.create();
        fixture.failJobOnce();

        assertThrows(IllegalStateException.class, fixture::finalizeApply);
        fixture.assertTaskAppliedOnceAndCommandRunning();
        assertEquals(RequirementPublicationStatus.COMMITTED,
                fixture.publications().findByOperationId("operation-recovery").orElseThrow().status());

        fixture.reclaimAndFinalizeAlreadyApplied();

        fixture.assertRecoveredExactlyOnce();
    }

    @Test
    void reclaimsAfterCommandCompletionFailureWithoutRepeatingEarlierEffects() {
        RecoveryFixture fixture = RecoveryFixture.create();
        fixture.failCommandOnce();

        assertThrows(IllegalStateException.class, fixture::finalizeApply);
        fixture.assertTaskAppliedOnceAndCommandRunning();
        assertEquals(RequirementPublicationStatus.COMMITTED,
                fixture.publications().findByOperationId("operation-recovery").orElseThrow().status());

        fixture.reclaimAndFinalizeAlreadyApplied();

        fixture.assertRecoveredExactlyOnce();
    }

    @Test
    void continuationConflictLeavesCurrentCommandRunning() {
        long now = 1_784_800_350_000L;
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand pending = RequirementStageCommand.pending(
                "command-continuation", "task-continuation", 0L, 1L, "REQUIREMENT_DELIVERY", "MATERIAL",
                0, 3, now + 60_000L, ScheduleResourceClass.GENERIC, "project-1", "", "P1", now);
        RequirementStageCommand conflict = RequirementStageCommand.pending(
                "existing-continuation", "task-continuation", 9L, 9L, "REQUIREMENT_DELIVERY", "NEXT",
                0, 3, now + 60_000L, ScheduleResourceClass.GENERIC, "project-1", "", "P1", now);
        commands.enqueue(pending);
        commands.enqueue(conflict);
        RequirementStageCommand claimed = commands.claim(pending.commandId(), "worker-1", now, 30_000L).orElseThrow();
        InMemoryRequirementStageFinalizationPort finalizer = new InMemoryRequirementStageFinalizationPort(
                commands, new InMemoryRequirementDeliveryJobStore());
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION, claimed.taskId(), 0L, 1L,
                RdTaskStatus.CREATED, List.of(), CommandDisposition.SUCCEEDED,
                new ContinuationSpec("REQUIREMENT_DELIVERY", "NEXT"), ExternalEffectReceipt.none());
        RequirementStageFinalization marker = finalizer.recordOutcome(
                finalizer.prepare(claimed, "worker-1", RdTaskStatus.CREATED, now), claimed, "worker-1", plan, now);
        RequirementStageCommand continuation = RequirementStageCommand.pending(
                "planned-continuation", "task-continuation", 0L, 1L, "REQUIREMENT_DELIVERY", "NEXT",
                0, 3, now + 60_000L, ScheduleResourceClass.GENERIC, "project-1", "", "P1", now);

        assertThrows(IllegalStateException.class, () -> finalizer.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        marker, claimed, "worker-1", plan, continuation, null,
                        RequirementStageFinalizationPort.JobDisposition.NONE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, now + 1L)));

        assertEquals(RequirementStageCommand.Status.RUNNING,
                commands.findById(claimed.commandId()).orElseThrow().status());
        assertEquals(conflict, commands.findById(conflict.commandId()).orElseThrow());
    }

    @Test
    void commitsOnlyTheMatchingConfirmedPublicationReceiptAfterFinalization() {
        long now = 1_784_800_200_000L;
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        taskStore.saveRequirementTask(RdRequirementTask.created("task-publication", new CreateRequirementTaskCommand(
                "Task title", "P1", "https://example.invalid/repo.git", "owner", "repo", "main",
                "deliver", List.of("criterion"), false), now));
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand pending = RequirementStageCommand.pending(
                "command-publication", "task-publication", 0L, 1L, "REQUIREMENT_DELIVERY", "PR_CREATING",
                0, 3, now + 60_000L, ScheduleResourceClass.GENERIC, "project-1", "", "P1", now);
        commands.enqueue(pending);
        RequirementStageCommand claimed = commands.claim(pending.commandId(), "worker-1", now, 30_000L)
                .orElseThrow();
        InMemoryRequirementPublicationStore publications = new InMemoryRequirementPublicationStore();
        RequirementPublicationLedger ledger = new RequirementPublicationLedger(publications);
        ledger.prepare(new RequirementPublicationPrepareCommand(
                "operation-publication", "task-publication", "stage-run-1", "main", "rd/task-publication", "sha"));
        ledger.markBranchConfirmed("operation-publication", "head-sha");
        ledger.markPullRequestConfirmed("operation-publication", "https://example.invalid/pr/7", 7);
        InMemoryRequirementStageFinalizationPort finalizer = new InMemoryRequirementStageFinalizationPort(
                commands,
                new InMemoryRequirementDeliveryJobStore(),
                taskStore,
                new CoordinatedRdTaskStatePersistence(taskStore, events),
                new SnowflakeIdGenerator(1, 1, () -> now),
                publications
        );
        RequirementStageFinalization prepared = finalizer.prepare(
                claimed, "worker-1", RdTaskStatus.CREATED, now);
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                "task-publication", 0L, 1L, RdTaskStatus.CREATED,
                List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING, "", "",
                        "https://example.invalid/pr/7", "", "publication committed")),
                CommandDisposition.SUCCEEDED,
                ContinuationSpec.terminal(),
                new ExternalEffectReceipt(
                        ExternalEffectReceipt.Kind.PUBLICATION,
                        "operation-publication",
                        "PR_CONFIRMED",
                        "{\"operationId\":\"operation-publication\",\"taskId\":\"task-publication\","
                                + "\"pullRequestUrl\":\"https://example.invalid/pr/7\",\"pullRequestNumber\":7}")
        );
        RequirementStageFinalization recorded = finalizer.recordOutcome(
                prepared, claimed, "worker-1", plan, now + 1L);

        RequirementStageExecutionPlan mismatchedReceipt = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                "task-publication", 0L, 1L, RdTaskStatus.CREATED,
                plan.mutations(), CommandDisposition.SUCCEEDED, ContinuationSpec.terminal(),
                new ExternalEffectReceipt(
                        ExternalEffectReceipt.Kind.PUBLICATION,
                        "operation-publication",
                        "PR_CONFIRMED",
                        "{\"operationId\":\"operation-publication\",\"taskId\":\"task-publication\","
                                + "\"pullRequestUrl\":\"https://example.invalid/pr/other\",\"pullRequestNumber\":7}"));
        assertThrows(IllegalStateException.class, () -> finalizer.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        recorded, claimed, "worker-1", mismatchedReceipt, null, null,
                        RequirementStageFinalizationPort.JobDisposition.NONE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY,
                        now + 2L)));
        assertEquals(RequirementPublicationStatus.PR_CONFIRMED,
                publications.findByOperationId("operation-publication").orElseThrow().status());
        assertEquals(RdTaskStatus.CREATED,
                taskStore.findRequirementTask("task-publication").orElseThrow().status());
        assertEquals(RequirementStageCommand.Status.RUNNING,
                commands.findById("command-publication").orElseThrow().status());

        finalizer.finalize(new RequirementStageFinalizationPort.FinalizationCommand(
                recorded, claimed, "worker-1", plan, null, null,
                RequirementStageFinalizationPort.JobDisposition.NONE,
                RequirementStageFinalizationPort.TaskMutationDisposition.APPLY,
                now + 2L));

        assertEquals(RequirementPublicationStatus.COMMITTED,
                publications.findByOperationId("operation-publication").orElseThrow().status());
        assertEquals(RdTaskStatus.MATERIAL_COLLECTING,
                taskStore.findRequirementTask("task-publication").orElseThrow().status());
        assertEquals(RequirementStageCommand.Status.SUCCEEDED,
                commands.findById("command-publication").orElseThrow().status());
    }

    @Test
    void retryableRecordedPlanDefersItsMutationThenReplaysItAtTheFinalAttempt() {
        long now = 1_784_800_300_000L;
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        RdRequirementTask created = RdRequirementTask.created("task-reclaim", new CreateRequirementTaskCommand(
                "Task title", "P1", "https://example.invalid/repo.git", "owner", "repo", "main",
                "deliver", List.of("criterion"), false), now);
        RdRequirementTask original = taskStore.saveRequirementTask(created);
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand pending = RequirementStageCommand.pending(
                "command-reclaim", "task-reclaim", original.version(), original.fencingToken(),
                "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING", 0, 2, now + 60_000L,
                ScheduleResourceClass.GENERIC, "project-1", "", "P1", now);
        commands.enqueue(pending);
        InMemoryRequirementStageFinalizationPort finalizer = new InMemoryRequirementStageFinalizationPort(
                commands, new InMemoryRequirementDeliveryJobStore(), taskStore,
                new CoordinatedRdTaskStatePersistence(taskStore, events),
                new SnowflakeIdGenerator(1, 1, () -> now));
        RequirementStageCommand first = commands.claim(pending.commandId(), "worker-1", now, 30_000L).orElseThrow();
        RequirementStageExecutionPlan retryable = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                original.taskId(), original.version(), original.fencingToken(), original.status(),
                List.of(RequirementTaskMutation.statusTransition(
                        original.status(), RdTaskStatus.FAILED_RETRYABLE, "", "{\"status\":\"FAILED\"}",
                        "", "provider unavailable", "provider unavailable")),
                CommandDisposition.RETRYABLE_TECHNICAL_FAILURE, ContinuationSpec.terminal(),
                ExternalEffectReceipt.none());
        RequirementStageFinalization firstMarker = finalizer.recordOutcome(
                finalizer.prepare(first, "worker-1", original.status(), now), first, "worker-1", retryable, now + 1L);

        finalizer.finalize(new RequirementStageFinalizationPort.FinalizationCommand(
                firstMarker, first, "worker-1", retryable, null, null,
                RequirementStageFinalizationPort.JobDisposition.FAIL_RETRYABLE,
                RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, now + 2L));

        assertEquals(RequirementStageCommand.Status.FAILED_RETRYABLE,
                commands.findById(first.commandId()).orElseThrow().status());
        assertEquals(original, taskStore.findRequirementTask(original.taskId()).orElseThrow());
        assertEquals(List.of(), events.listByTask(original.taskId()));
        assertEquals(firstMarker, finalizer.findLatestPrepared(first.commandId()).orElseThrow());
        RequirementStageCommand second = commands.claim(first.commandId(), "worker-2", now + 3L, 30_000L).orElseThrow();
        assertEquals(2, second.attemptNo());
        assertThrows(IllegalStateException.class,
                () -> commands.complete(first.commandId(), first.attemptNo(), "worker-1", now + 4L));

        finalizer.finalize(new RequirementStageFinalizationPort.FinalizationCommand(
                firstMarker, second, "worker-2", retryable, null, null,
                RequirementStageFinalizationPort.JobDisposition.FAIL_RETRYABLE,
                RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, now + 6L));

        assertEquals(RequirementStageCommand.Status.DEAD_LETTERED,
                commands.findById(second.commandId()).orElseThrow().status());
        assertEquals(RdTaskStatus.FAILED_RETRYABLE,
                taskStore.findRequirementTask(original.taskId()).orElseThrow().status());
    }

    private static final class RecoveryFixture {
        private final long now = 1_784_800_400_000L;
        private final InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        private final InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        private final InMemoryRequirementStageCommandStore durableCommands = new InMemoryRequirementStageCommandStore();
        private final FailOnceCompletionCommandStore commands = new FailOnceCompletionCommandStore(durableCommands);
        private final InMemoryRequirementDeliveryJobStore durableJobs = new InMemoryRequirementDeliveryJobStore();
        private final RequirementDeliveryJobStore jobs = mock(
                RequirementDeliveryJobStore.class, delegatesTo(durableJobs));
        private final InMemoryRequirementPublicationStore durablePublications = new InMemoryRequirementPublicationStore();
        private final RequirementPublicationStore publications = mock(
                RequirementPublicationStore.class, delegatesTo(durablePublications));
        private final InMemoryRequirementStageFinalizationPort finalizer;
        private final RequirementStageExecutionPlan plan;
        private final RequirementStageFinalization marker;
        private RequirementStageCommand command;
        private RequirementDeliveryJob job;

        private RecoveryFixture() {
            taskStore.saveRequirementTask(RdRequirementTask.created("task-recovery", new CreateRequirementTaskCommand(
                    "Task title", "P1", "https://example.invalid/repo.git", "owner", "repo", "main",
                    "deliver", List.of("criterion"), false), now));
            RequirementStageCommand pending = RequirementStageCommand.pending(
                    "command-recovery", "task-recovery", 0L, 1L, "REQUIREMENT_DELIVERY", "PR_CREATING",
                    0, 3, now + 120_000L, ScheduleResourceClass.GENERIC, "project-1", "", "P1", now);
            durableCommands.enqueue(pending);
            command = durableCommands.claim(pending.commandId(), "worker-1", now, 30_000L).orElseThrow();
            job = durableJobs.enqueue(RequirementDeliveryJob.pending("job-recovery", "task-recovery", 3, now));
            job = durableJobs.claim(job.taskId(), "worker-1", now, 30_000L).orElseThrow();
            RequirementPublicationLedger ledger = new RequirementPublicationLedger(publications);
            ledger.prepare(new RequirementPublicationPrepareCommand(
                    "operation-recovery", "task-recovery", "stage-run", "main", "rd/recovery", "sha"));
            ledger.markBranchConfirmed("operation-recovery", "head");
            ledger.markPullRequestConfirmed("operation-recovery", "https://example.invalid/pr/8", 8);
            finalizer = new InMemoryRequirementStageFinalizationPort(
                    commands, jobs, taskStore, new CoordinatedRdTaskStatePersistence(taskStore, events),
                    new SnowflakeIdGenerator(1, 1, () -> now), publications);
            plan = new RequirementStageExecutionPlan(
                    RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION, "task-recovery", 0L, 1L,
                    RdTaskStatus.CREATED, List.of(RequirementTaskMutation.statusTransition(
                    RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING, "", "{}",
                    "https://example.invalid/pr/8", "", "publication applied")),
                    CommandDisposition.SUCCEEDED, ContinuationSpec.terminal(), new ExternalEffectReceipt(
                    ExternalEffectReceipt.Kind.PUBLICATION, "operation-recovery", "PR_CONFIRMED",
                    "{\"operationId\":\"operation-recovery\",\"taskId\":\"task-recovery\","
                            + "\"pullRequestUrl\":\"https://example.invalid/pr/8\",\"pullRequestNumber\":8}"));
            marker = finalizer.recordOutcome(finalizer.prepare(command, "worker-1", RdTaskStatus.CREATED, now),
                    command, "worker-1", plan, now + 1L);
        }

        static RecoveryFixture create() {
            return new RecoveryFixture();
        }

        RequirementStageCommandStore commands() { return commands; }
        RequirementDeliveryJobStore jobs() { return jobs; }
        RequirementPublicationStore publications() { return publications; }

        void failPublicationOnce() {
            doThrow(new IllegalStateException("publication commit unavailable"))
                    .doAnswer(invocation -> durablePublications.save(invocation.getArgument(0, RequirementPublication.class)))
                    .when(publications).save(any(RequirementPublication.class));
        }

        void failJobOnce() {
            doThrow(new IllegalStateException("job completion unavailable"))
                    .doAnswer(invocation -> durableJobs.complete(
                            invocation.getArgument(0, String.class), invocation.getArgument(1, String.class),
                            invocation.getArgument(2, Long.class)))
                    .when(jobs).complete(any(), any(), anyLong());
        }

        void failCommandOnce() {
            commands.failNextCompletion();
        }

        void finalizeApply() {
            finalize(RequirementStageFinalizationPort.TaskMutationDisposition.APPLY);
        }

        void reclaimAndFinalizeAlreadyApplied() {
            command = durableCommands.claim(command.commandId(), "worker-2", now + 30_001L, 30_000L).orElseThrow();
            if (durableJobs.findByTask(job.taskId()).orElseThrow().status()
                    == com.wish.rd.engine.requirement.job.model.RequirementDeliveryJobStatus.RUNNING) {
                job = durableJobs.claim(job.taskId(), "worker-2", now + 30_001L, 30_000L).orElseThrow();
            }
            finalize(RequirementStageFinalizationPort.TaskMutationDisposition.ALREADY_APPLIED);
        }

        void assertTaskAppliedOnceAndCommandRunning() {
            assertEquals(1, events.listByTask("task-recovery").size());
            assertEquals(RequirementStageCommand.Status.RUNNING,
                    durableCommands.findById(command.commandId()).orElseThrow().status());
        }

        void assertRecoveredExactlyOnce() {
            assertEquals(1, events.listByTask("task-recovery").size());
            assertEquals(RequirementStageCommand.Status.SUCCEEDED,
                    durableCommands.findById(command.commandId()).orElseThrow().status());
            assertEquals(RequirementPublicationStatus.COMMITTED,
                    durablePublications.findByOperationId("operation-recovery").orElseThrow().status());
        }

        private void finalize(RequirementStageFinalizationPort.TaskMutationDisposition disposition) {
            finalizer.finalize(new RequirementStageFinalizationPort.FinalizationCommand(
                    marker, command, command.leaseOwner(), plan, null, job,
                    RequirementStageFinalizationPort.JobDisposition.COMPLETE, disposition,
                    "worker-1".equals(command.leaseOwner()) ? now + 2L : now + 30_002L));
        }
    }

    @Test
    void exhaustedCheckpointBoundRoleCommandWritesItsExactAgentRoleProvenanceAndSettlesOnlyItsCheckpoint() {
        ExhaustionFixture fixture = ExhaustionFixture.checkpointBoundRole();

        RequirementStageFinalizationPort.ExhaustionResult result = fixture.finalizer.exhaustCommand(fixture.command());

        RequirementStageCommand completed = fixture.commands.findById(fixture.claimed.commandId()).orElseThrow();
        RdRequirementTask task = fixture.taskStore.findRequirementTask(fixture.taskId).orElseThrow();
        TaskRetryFailureProvenance provenance = fixture.provenanceStore.findExact(
                fixture.taskId, task.status(), task.version(), task.fencingToken()).orElseThrow();
        TaskRetryCheckpoint checkpoint = fixture.checkpoints.find(fixture.checkpointId).orElseThrow();

        assertEquals(RequirementStageCommand.Status.DEAD_LETTERED, completed.status());
        assertEquals(RdTaskStatus.FAILED_RETRYABLE, task.status());
        assertTrue(task.version() > fixture.sourceVersion);
        assertTrue(task.fencingToken() > fixture.sourceFence);
        assertEquals(TaskFailurePhase.AGENT_ROLE, provenance.failurePhase());
        assertNotEquals(TaskFailurePhase.CONTEXT, provenance.failurePhase());
        assertEquals("ROLE_EXECUTION:CODING_AGENT", provenance.failedStage());
        assertEquals(task.version(), provenance.failedTaskVersion());
        assertEquals(task.fencingToken(), provenance.failedTaskFencingToken());
        assertEquals(RdTaskStatus.FAILED_RETRYABLE, provenance.outcomeStatus());
        assertEquals(TaskRetryCheckpointStatus.FAILED_RETRYABLE, checkpoint.status());
        assertEquals(completed.commandId(), result.completedCommand().commandId());
        assertEquals(provenance.provenanceId(), result.provenance().provenanceId());
    }

    @Test
    void exhaustionSettlesTheCommandsOwnCheckpointInsteadOfAnyOtherActiveCheckpoint() {
        // Store forbids two active checkpoints per task; blank retryCheckpointId must settle none.
        ExhaustionFixture fixture = ExhaustionFixture.checkpointBoundRole();
        RequirementStageCommand unbound = RequirementStageCommand.pending(
                "command-unbound", fixture.taskId, fixture.sourceVersion, fixture.sourceFence,
                "REQUIREMENT_DELIVERY", "CONTEXT_BUILDING", 0, 1, fixture.now + 60_000L,
                ScheduleResourceClass.GENERIC, Set.of(ScheduleResourceClass.GENERIC),
                "project-1", "", "P1", "", "", 0L, "", fixture.now);
        fixture.commands.enqueue(unbound);
        RequirementStageCommand claimedUnbound = fixture.commands.claim(
                unbound.commandId(), "worker-1", fixture.now, 30_000L).orElseThrow();
        var draft = new RequirementStageFinalizationPort.ExhaustionProvenanceDraft(
                "provenance-unbound", claimedUnbound.stage(), TaskFailurePhase.CONTEXT,
                "", "", "", "", "", "", "TECHNICAL_EXHAUSTED");

        fixture.finalizer.exhaustCommand(new RequirementStageFinalizationPort.ExhaustionCommand(
                claimedUnbound, "worker-1", RdTaskStatus.RECOVERING, fixture.sourceVersion, fixture.sourceFence,
                RdTaskStatus.FAILED_RETRYABLE, TaskRetryCheckpointStatus.FAILED_RETRYABLE, draft,
                "unbound exhausted", "{}", fixture.now + 5L));

        assertEquals(TaskRetryCheckpointStatus.DISPATCHED,
                fixture.checkpoints.find(fixture.checkpointId).orElseThrow().status());
        assertEquals(RequirementStageCommand.Status.DEAD_LETTERED,
                fixture.commands.findById(claimedUnbound.commandId()).orElseThrow().status());
    }

    @Test
    void exhaustionRollsBackCommandAndTaskWhenItsProvenanceInsertConflicts() {
        ExhaustionFixture fixture = ExhaustionFixture.checkpointBoundRole();
        long nextVersion = fixture.sourceVersion + 1L;
        long nextFence = fixture.sourceFence + 1L;
        fixture.provenanceStore.save(new TaskRetryFailureProvenance(
                "provenance-conflict", fixture.taskId, "other-command", 1,
                "CONTEXT_BUILDING", TaskFailurePhase.CONTEXT,
                RdTaskStatus.FAILED_RETRYABLE, nextVersion, nextFence,
                "", "", "", "", "", "", "OTHER", fixture.now));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> fixture.finalizer.exhaustCommand(fixture.command()));

        assertTrue(failure.getMessage().contains("failure provenance identity conflict")
                || failure.getMessage().contains("provenance"));
        assertEquals(RequirementStageCommand.Status.RUNNING,
                fixture.commands.findById(fixture.claimed.commandId()).orElseThrow().status());
        RdRequirementTask task = fixture.taskStore.findRequirementTask(fixture.taskId).orElseThrow();
        assertEquals(RdTaskStatus.RECOVERING, task.status());
        assertEquals(fixture.sourceVersion, task.version());
        assertEquals(fixture.sourceFence, task.fencingToken());
        assertEquals(TaskRetryCheckpointStatus.DISPATCHED,
                fixture.checkpoints.find(fixture.checkpointId).orElseThrow().status());
    }

    @Test
    void exhaustionCancelsUnusedPendingAttemptsBoundToThatCheckpoint() {
        ExhaustionFixture fixture = ExhaustionFixture.checkpointBoundRole();
        AgentStageRun pendingQa = AgentStageRun.pending(
                "qa-pending", fixture.taskId, AgentRole.QA_AGENT, 1,
                fixture.taskId + ":QA_AGENT:1", fixture.now);
        fixture.stages.save(pendingQa);
        fixture.bindings.save(new TaskRetryAttemptBinding(
                "binding-qa", fixture.checkpointId, TaskRetryAttemptKind.AGENT_STAGE,
                AgentRole.QA_AGENT, pendingQa.stageRunId(), "", 1, 1));

        fixture.finalizer.exhaustCommand(fixture.command());

        assertEquals(AgentStageStatus.CANCELLED,
                fixture.stages.findById(pendingQa.stageRunId()).orElseThrow().status());
        assertEquals(AgentStageStatus.CANCELLED,
                fixture.stages.findById(fixture.targetAttemptId).orElseThrow().status());
    }

    @Test
    void exhaustionResumesAnAlreadyTerminalCommandAndStillWritesItsProvenance() {
        ExhaustionFixture fixture = ExhaustionFixture.checkpointBoundRole();
        RequirementStageCommand alreadyDead = fixture.commands.fail(
                fixture.claimed, "worker-1", "stage command deadline exceeded", fixture.now + 1L);
        assertEquals(RequirementStageCommand.Status.DEAD_LETTERED, alreadyDead.status());

        RequirementStageFinalizationPort.ExhaustionResult result = fixture.finalizer.exhaustCommand(
                new RequirementStageFinalizationPort.ExhaustionCommand(
                        alreadyDead, "worker-1", RdTaskStatus.RECOVERING,
                        fixture.sourceVersion, fixture.sourceFence,
                        RdTaskStatus.FAILED_RETRYABLE, TaskRetryCheckpointStatus.FAILED_RETRYABLE,
                        fixture.command().provenanceDraft(),
                        "stage command deadline exceeded", "{}", fixture.now + 5L));

        RdRequirementTask task = fixture.taskStore.findRequirementTask(fixture.taskId).orElseThrow();
        TaskRetryFailureProvenance provenance = fixture.provenanceStore.findExact(
                fixture.taskId, task.status(), task.version(), task.fencingToken()).orElseThrow();
        assertEquals(RdTaskStatus.FAILED_RETRYABLE, task.status());
        assertEquals(TaskFailurePhase.AGENT_ROLE, provenance.failurePhase());
        assertEquals(alreadyDead.commandId(), result.completedCommand().commandId());
        assertEquals(TaskRetryCheckpointStatus.FAILED_RETRYABLE,
                fixture.checkpoints.find(fixture.checkpointId).orElseThrow().status());
    }

    @Test
    void lastAttemptPublicationWaitReconcileFinalizationWritesCanonicalProvenanceAndRecoveryRoute() {
        long now = 1_784_910_500_000L;
        String taskId = "task-publication";
        String patchSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String deliveryJson = """
                {"multiAgentStatus":"SUCCESS","multiAgentStages":[
                {"role":"CODING_AGENT","success":true,"candidatePatch":{"sha256":"%s","bytes":1,"artifactUri":"s3://patch"},"resultJson":{}}]}
                """.formatted(patchSha);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        taskStore.saveRequirementTask(RdRequirementTask.created(taskId, new CreateRequirementTaskCommand(
                        "Task title", "P1", "https://example.invalid/repo.git", "owner", "repo", "main",
                        "deliver", List.of("criterion"), false), now)
                .withState(RdTaskStatus.VALIDATING, "", deliveryJson, "", "", now)
                .withConcurrency(8L, 15L));
        String workBranch = "requirement/" + taskId;
        String operationId = RequirementOperationId.of(taskId, "main", workBranch, patchSha);
        InMemoryRequirementPublicationStore publications = new InMemoryRequirementPublicationStore();
        RequirementPublicationLedger ledger = new RequirementPublicationLedger(publications);
        ledger.prepare(new RequirementPublicationPrepareCommand(
                operationId, taskId, "stage-seed", "main", workBranch, patchSha));
        ledger.markUnknownRemoteResult(operationId, "GitHub POST timed out");
        InMemoryRequirementPolicyRunStore policyRuns = new InMemoryRequirementPolicyRunStore();
        String planJson = "{}";
        policyRuns.createOrGet(new RequirementPolicyRun(
                "policy-1", taskId, 8L, 15L, planJson, RequirementPolicyRun.canonicalJsonDigest(planJson),
                "", "", "", RequirementPolicyRunState.PLAN_READY,
                8L, 15L, null, null, "", "", "", 0L, "", "", 0L, 0L, now, now));
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand pending = RequirementStageCommand.pending(
                "command-publication", taskId, 8L, 15L, "REQUIREMENT_DELIVERY", "PUBLICATION",
                2, 3, now + 60_000L, ScheduleResourceClass.GENERIC, Set.of(ScheduleResourceClass.GENERIC),
                "project-1", "", "P1", "policy-1", "", 0L, "", now);
        commands.enqueue(pending);
        RequirementStageCommand claimed = commands.claim(pending.commandId(), "worker-1", now, 30_000L)
                .orElseThrow();
        InMemoryTaskRetryFailureProvenanceStore provenanceStore = new InMemoryTaskRetryFailureProvenanceStore();
        InMemoryRequirementStageFinalizationPort finalizer = new InMemoryRequirementStageFinalizationPort(
                commands, new InMemoryRequirementDeliveryJobStore(), taskStore,
                new CoordinatedRdTaskStatePersistence(taskStore, events),
                new SnowflakeIdGenerator(1, 1, () -> now), publications, policyRuns,
                provenanceStore, null, null, null);
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION, taskId, 8L, 15L, RdTaskStatus.VALIDATING,
                List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.VALIDATING, RdTaskStatus.FAILED_RETRYABLE, "", deliveryJson, "",
                        "publication is waiting for remote reconciliation; do not replay push/PR", "")),
                CommandDisposition.RETRYABLE_TECHNICAL_FAILURE, ContinuationSpec.terminal(),
                new ExternalEffectReceipt(
                        ExternalEffectReceipt.Kind.PUBLICATION, operationId, "UNKNOWN_REMOTE_RESULT",
                        "{\"taskId\":\"" + taskId + "\",\"operationId\":\"" + operationId + "\"}"));
        RequirementStageFinalization marker = finalizer.recordOutcome(
                finalizer.prepare(claimed, "worker-1", RdTaskStatus.VALIDATING, now),
                claimed, "worker-1", plan, now + 1L);
        finalizer.finalize(new RequirementStageFinalizationPort.FinalizationCommand(
                marker, claimed, "worker-1", plan, null, null,
                RequirementStageFinalizationPort.JobDisposition.NONE,
                RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, now + 2L));

        RdRequirementTask failedTask = taskStore.findRequirementTask(taskId).orElseThrow();
        TaskRetryFailureProvenance provenance = provenanceStore.findExact(
                taskId, failedTask.status(), failedTask.version(), failedTask.fencingToken()).orElseThrow();
        assertEquals("PUBLICATION:" + operationId, provenance.failedStage());
        assertEquals(TaskFailurePhase.PR_PUBLICATION, provenance.failurePhase());
        assertEquals(operationId, provenance.publicationOperationId());
        assertEquals("policy-1", provenance.sourcePolicyRunId());
        assertEquals(RequirementPolicyRun.canonicalJsonDigest(planJson), provenance.sourcePlanDigest());
        assertEquals("UNKNOWN_REMOTE_RESULT", provenance.failureKind());
        assertEquals(failedTask.version(), provenance.failedTaskVersion());
        assertEquals(failedTask.fencingToken(), provenance.failedTaskFencingToken());

        TaskFailureRecoveryService recovery = new TaskFailureRecoveryService(
                new FakeRecoveryTaskPort(failedTask), new InMemoryAgentStageRunStore(),
                new InMemoryAgentStageArtifactStore(), new InMemoryRetrievalRunStore(),
                new InMemoryAiReviewRunStore(), new InMemoryTaskRetryCheckpointStore(),
                provenanceStore, new TaskRetryPointResolver(), new TaskFailureDiagnosticParser());
        TaskFailureRecoverySnapshot snapshot = recovery.snapshot(taskId);
        TaskRetryRoute route = new TaskRetryRoutePlanner().plan(snapshot.retryPoint());
        assertEquals("PUBLICATION:" + operationId, route.firstStage());
    }

    @Test
    void publicationFailureWithoutProvenanceStoreFailsClosed() {
        long now = 1_784_800_360_000L;
        String taskId = "task-publication-missing-store";
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        taskStore.saveRequirementTask(RdRequirementTask.created(taskId, new CreateRequirementTaskCommand(
                "Task title", "P1", "https://example.invalid/repo.git", "owner", "repo", "main",
                "deliver", List.of("criterion"), false), now));
        InMemoryRequirementPublicationStore publications = new InMemoryRequirementPublicationStore();
        InMemoryRequirementPolicyRunStore policyRuns = new InMemoryRequirementPolicyRunStore();
        policyRuns.createOrGet(new RequirementPolicyRun(
                "policy-1", taskId, 1L, 1L, "{}", RequirementPolicyRun.canonicalJsonDigest("{}"),
                "", "", "", RequirementPolicyRunState.PLAN_READY,
                1L, 1L, null, null, "", "", "", 0L, "", "", 0L, 0L, now, now));
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand pending = RequirementStageCommand.pending(
                "command-publication-missing-store", taskId, 1L, 1L, "REQUIREMENT_DELIVERY", "PUBLICATION",
                0, 3, now + 60_000L, ScheduleResourceClass.GENERIC, Set.of(ScheduleResourceClass.GENERIC),
                "project-1", "", "P1", "policy-1", "", 0L, "", now);
        commands.enqueue(pending);
        RequirementStageCommand claimed = commands.claim(pending.commandId(), "worker-1", now, 30_000L)
                .orElseThrow();
        InMemoryRequirementStageFinalizationPort finalizer = new InMemoryRequirementStageFinalizationPort(
                commands, new InMemoryRequirementDeliveryJobStore(), taskStore,
                new CoordinatedRdTaskStatePersistence(taskStore, events),
                new SnowflakeIdGenerator(1, 1, () -> now), publications, policyRuns);
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION, taskId, 1L, 1L, RdTaskStatus.VALIDATING,
                List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.VALIDATING, RdTaskStatus.FAILED_RETRYABLE, "", "{}", "",
                        "publication is waiting for remote reconciliation; do not replay push/PR", "")),
                CommandDisposition.RETRYABLE_TECHNICAL_FAILURE, ContinuationSpec.terminal(),
                new ExternalEffectReceipt(
                        ExternalEffectReceipt.Kind.PUBLICATION, "op-1", "UNKNOWN_REMOTE_RESULT", "{}"));
        RequirementStageFinalization marker = finalizer.recordOutcome(
                finalizer.prepare(claimed, "worker-1", RdTaskStatus.VALIDATING, now),
                claimed, "worker-1", plan, now + 1L);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> finalizer.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        marker, claimed, "worker-1", plan, null, null,
                        RequirementStageFinalizationPort.JobDisposition.NONE,
                        RequirementStageFinalizationPort.TaskMutationDisposition.APPLY, now + 2L)));
        assertTrue(thrown.getMessage().contains("provenance"));
    }

    @Test
    void exhaustionIsANoOpWhenTheTaskFailureAndProvenanceAreAlreadyDurable() {
        ExhaustionFixture fixture = ExhaustionFixture.checkpointBoundRole();
        RequirementStageFinalizationPort.ExhaustionResult first = fixture.finalizer.exhaustCommand(fixture.command());
        RequirementStageCommand terminal = fixture.commands.findById(fixture.claimed.commandId()).orElseThrow();

        RequirementStageFinalizationPort.ExhaustionResult second = fixture.finalizer.exhaustCommand(
                new RequirementStageFinalizationPort.ExhaustionCommand(
                        terminal, "worker-1", RdTaskStatus.RECOVERING,
                        fixture.sourceVersion, fixture.sourceFence,
                        RdTaskStatus.FAILED_RETRYABLE, TaskRetryCheckpointStatus.FAILED_RETRYABLE,
                        fixture.command().provenanceDraft(),
                        "role command task is not executing", "{}", fixture.now + 9L));

        assertEquals(first.provenance().provenanceId(), second.provenance().provenanceId());
        assertEquals(first.failedTask().version(), second.failedTask().version());
        assertEquals(first.failedTask().fencingToken(), second.failedTask().fencingToken());
        assertEquals(1, fixture.events.listByTask(fixture.taskId).size());
    }

    private static final class FakeRecoveryTaskPort implements com.wish.rd.engine.retry.TaskRetryTaskPort {
        private final RdRequirementTask task;

        private FakeRecoveryTaskPort(RdRequirementTask task) {
            this.task = task;
        }

        @Override
        public RdRequirementTask getRequirementTask(String taskId) {
            return task;
        }

        @Override
        public RdRequirementTask markRecovering(String taskId, String reason) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public RdRequirementTask markRetryPreparationFailed(String taskId, String reason) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private static final class ExhaustionFixture {
        private final String taskId = "task-exhaust-1";
        private final String checkpointId = "101";
        private final String targetAttemptId = "coding-retry-2";
        private final long sourceVersion = 12L;
        private final long sourceFence = 13L;
        private final long now = 1_784_900_000_000L;
        private final InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        private final InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        private final InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        private final InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        private final InMemoryTaskRetryAttemptBindingStore bindings = new InMemoryTaskRetryAttemptBindingStore();
        private final InMemoryTaskRetryFailureProvenanceStore provenanceStore =
                new InMemoryTaskRetryFailureProvenanceStore();
        private final InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        private final InMemoryRequirementStageFinalizationPort finalizer;
        private final RequirementStageCommand claimed;

        private ExhaustionFixture() {
            taskStore.saveRequirementTask(RdRequirementTask.created(taskId, new CreateRequirementTaskCommand(
                            "Task title", "P1", "https://example.invalid/repo.git", "owner", "repo", "main",
                            "deliver", List.of("criterion"), false), now)
                    .withState(RdTaskStatus.RECOVERING, "", "{}", "", "recovering", now)
                    .withConcurrency(sourceVersion, sourceFence));
            TaskRetryCheckpoint checkpoint = new TaskRetryCheckpoint(
                    checkpointId, taskId, TaskFailurePhase.AGENT_ROLE, AgentRole.CODING_AGENT,
                    "coding-1", "", "", 1, taskId + ":11:AGENT_ROLE:CODING_AGENT",
                    RdTaskStatus.FAILED_NEEDS_HUMAN, 11L, 12L,
                    "command-old", "ROLE_EXECUTION:CODING_AGENT", "policy-1", "",
                    "sha256:" + "a".repeat(64), "", "", 0L, 0L, "", 101L,
                    "", List.of(), TaskRetryCheckpointStatus.CREATED, "provider missing", "", now, now);
            checkpoints.createOrGet(checkpoint);
            checkpoints.dispatch(checkpointId, sourceVersion, sourceFence, "command-retry", now);
            stages.save(AgentStageRun.pending(
                            "coding-1", taskId, AgentRole.CODING_AGENT, 1,
                            taskId + ":CODING_AGENT:1", now)
                    .withStatus(AgentStageStatus.FAILED_RETRYABLE, "PROVIDER", "missing key", now));
            stages.save(AgentStageRun.pending(
                    targetAttemptId, taskId, AgentRole.CODING_AGENT, 2,
                    taskId + ":CODING_AGENT:2", now));
            TaskRetryAttemptBinding binding = new TaskRetryAttemptBinding(
                    "binding-coding", checkpointId, TaskRetryAttemptKind.AGENT_STAGE,
                    AgentRole.CODING_AGENT, targetAttemptId, "", 2, 0);
            bindings.save(binding);
            RequirementStageCommand pending = RequirementStageCommand.pending(
                    "command-retry", taskId, sourceVersion, sourceFence,
                    AgentRole.CODING_AGENT.name(), "ROLE_EXECUTION:CODING_AGENT",
                    0, 1, now + 60_000L, ScheduleResourceClass.GENERIC,
                    Set.of(ScheduleResourceClass.GENERIC), "project-1", "", "P1", "policy-1",
                    checkpointId, 101L, binding.bindingId(), now);
            commands.enqueue(pending);
            claimed = commands.claim(pending.commandId(), "worker-1", now, 30_000L).orElseThrow();
            finalizer = new InMemoryRequirementStageFinalizationPort(
                    commands, new InMemoryRequirementDeliveryJobStore(), taskStore,
                    new CoordinatedRdTaskStatePersistence(taskStore, events),
                    new SnowflakeIdGenerator(1, 1, () -> now), null, null,
                    provenanceStore, checkpoints, bindings, stages);
        }

        private static ExhaustionFixture checkpointBoundRole() {
            return new ExhaustionFixture();
        }

        private RequirementStageFinalizationPort.ExhaustionCommand command() {
            var factory = new TaskRetryExhaustionProvenanceFactory(checkpoints, bindings);
            var draft = factory.draftFor(claimed, "provenance-exhaust-1", "TECHNICAL_EXHAUSTED",
                    TaskRetryExhaustionProvenanceFactory.ExecutionContext.empty());
            return new RequirementStageFinalizationPort.ExhaustionCommand(
                    claimed, "worker-1", RdTaskStatus.RECOVERING, sourceVersion, sourceFence,
                    RdTaskStatus.FAILED_RETRYABLE, TaskRetryCheckpointStatus.FAILED_RETRYABLE, draft,
                    "role command task is not executing: " + claimed.commandId(), "{}", now + 3L);
        }
    }

    private static final class FailOnceCompletionCommandStore implements RequirementStageCommandStore {
        private final RequirementStageCommandStore delegate;
        private boolean failNextCompletion;

        private FailOnceCompletionCommandStore(RequirementStageCommandStore delegate) {
            this.delegate = delegate;
        }

        void failNextCompletion() { failNextCompletion = true; }
        @Override public RequirementStageCommand enqueue(RequirementStageCommand command) { return delegate.enqueue(command); }
        @Override public Optional<RequirementStageCommand> findById(String commandId) { return delegate.findById(commandId); }
        @Override public List<RequirementStageCommand> claimBatch(String owner, long now, long lease, int size) {
            return delegate.claimBatch(owner, now, lease, size);
        }
        @Override public List<RequirementStageCommand> claimFairBatch(
                List<String> ids, String owner, long now, long lease, RequirementDeliverySchedulingPolicy policy
        ) { return delegate.claimFairBatch(ids, owner, now, lease, policy); }
        @Override public Optional<RequirementStageCommand> claim(String id, String owner, long now, long lease) {
            return delegate.claim(id, owner, now, lease);
        }
        @Override public RequirementStageCommand complete(RequirementStageCommand command, String owner, long now) {
            if (failNextCompletion) {
                failNextCompletion = false;
                throw new IllegalStateException("command completion unavailable");
            }
            return delegate.complete(command, owner, now);
        }
        @Override public List<RequirementStageCommand> recoverable(long now, int limit) {
            return delegate.recoverable(now, limit);
        }
    }
}
