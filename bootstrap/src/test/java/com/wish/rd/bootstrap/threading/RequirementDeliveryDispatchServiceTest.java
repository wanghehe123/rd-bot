package com.wish.rd.bootstrap.threading;

import com.wish.rd.engine.requirement.RequirementDeliveryEngine;
import com.wish.rd.engine.requirement.job.RequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.RequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.job.RequirementStageExecutor;
import com.wish.rd.engine.requirement.job.RequirementStageFinalizationPort;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJob;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJobStatus;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.requirement.job.model.RequirementStageFinalization;
import com.wish.rd.engine.requirement.job.model.RequirementTaskMutation;
import com.wish.rd.engine.requirement.model.RequirementDeliveryResult;
import com.wish.rd.engine.requirement.policy.RequirementPolicyTransactionPort;
import com.wish.rd.engine.requirement.policy.RequirementPolicyRunStore;
import com.wish.rd.engine.requirement.policy.impl.InMemoryRequirementPolicyRunStore;
import com.wish.rd.engine.requirement.policy.model.ApproveRequirementPolicyCommand;
import com.wish.rd.engine.requirement.policy.model.RecordRequirementPolicyDecisionCommand;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApplyDisposition;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApplyResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApprovalResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationProposal;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationPreparationResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyResumeResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRunState;
import com.wish.rd.engine.scheduling.model.FairScheduleLimits;
import com.wish.rd.engine.scheduling.model.RequirementDeliverySchedulingPolicy;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.engine.retry.TaskRetryAttemptBindingStore;
import com.wish.rd.engine.retry.TaskRetryCheckpointStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryAttemptBindingStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryCheckpointStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryFailureProvenanceStore;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryAttemptBinding;
import com.wish.rd.engine.retry.model.TaskRetryAttemptKind;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskRetryFailureProvenance;
import com.wish.rd.engine.retry.model.TaskRetryPoint;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageFinalizationPort;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.rag.runtime.impl.CoordinatedRdTaskStatePersistence;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.project.agent.AgentExecutionProfileService;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileStore;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfile;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.engine.agent.model.AgentRole;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

class RequirementDeliveryDispatchServiceTest {

    @Test
    void approvalResumeConsumesDurableContinuationWithoutGenericExecutorOrFinalizer() {
        long now = System.currentTimeMillis();
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand resume = approvalResumeCommand("approval-resume-success", "approval-success", 10L, 20L)
                .claimed("test-worker", now + 60_000L, now);
        RequirementStageCommand next = RequirementStageCommand.pending(
                "approval-first-role", "approval-success", 11L, 21L,
                AgentRole.REQUIREMENT_REVIEWER.name(),
                "ROLE_EXECUTION:" + AgentRole.REQUIREMENT_REVIEWER.name(),
                0, 3, now + 1_000L, ScheduleResourceClass.PROVIDER,
                Set.of(ScheduleResourceClass.PROVIDER), "project-1", "provider", "P1",
                resume.policyRunId(), now);
        commands.enqueue(next);
        RequirementDeliveryJobStore jobs = mock(RequirementDeliveryJobStore.class);
        RequirementStageExecutor executor = mock(RequirementStageExecutor.class);
        RequirementStageFinalizationPort finalizer = mock(RequirementStageFinalizationPort.class);
        AtomicInteger scheduled = new AtomicInteger();
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class),
                new TaskExecutorAdapter(ignored -> scheduled.incrementAndGet()),
                jobs, SnowflakeIdGenerator.defaultGenerator(), mock(RagStreamTaskRegistry.class),
                "test-worker", 3, 60_000L, null, null, commands, executor, finalizer,
                RequirementDeliverySchedulingPolicy.defaults(), null);
        RecordingPolicyTransactionPort policyPort = new RecordingPolicyTransactionPort(
                new RequirementPolicyResumeResult(appliedPolicyRun("approval-success", resume, 11L, 21L, now),
                        resume.succeeded(now), next, 11L, 21L));
        dispatcher.setRequirementPolicyTransactionPort(policyPort);

        invokeRunClaimedCommand(dispatcher, resume, new CompletableFuture<>());

        assertEquals(1, policyPort.consumeCalls.get());
        assertEquals(1, scheduled.get(), "the durable first-role command must be scheduled once");
        verifyNoInteractions(executor, finalizer);
        assertNoUmbrellaJobMutation(jobs);
        assertEquals(1L, dispatcher.metricsSnapshot().completed());
        assertEquals(1L, dispatcher.metricsSnapshot().enqueued());
    }

    @Test
    void approvalResumeTreatsCommittedConsumerResultAsAuthoritativeWhenTerminalHeartbeatRaces() {
        long now = System.currentTimeMillis();
        ControllableTaskScheduler heartbeatScheduler = new ControllableTaskScheduler();
        HeartbeatRacingStageCommandStore commands = new HeartbeatRacingStageCommandStore();
        RequirementStageCommand resume = approvalResumeCommand("approval-resume-terminal-heartbeat", "approval-heartbeat", 10L, 20L)
                .claimed("test-worker", now + 60_000L, now);
        RequirementStageCommand next = RequirementStageCommand.pending(
                "approval-heartbeat-first-role", "approval-heartbeat", 11L, 21L,
                AgentRole.REQUIREMENT_REVIEWER.name(),
                "ROLE_EXECUTION:" + AgentRole.REQUIREMENT_REVIEWER.name(),
                0, 3, now + 1_000L, ScheduleResourceClass.PROVIDER,
                Set.of(ScheduleResourceClass.PROVIDER), "project-1", "provider", "P1",
                resume.policyRunId(), now);
        commands.enqueue(next);
        AtomicInteger scheduled = new AtomicInteger();
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                null, new TaskExecutorAdapter(ignored -> scheduled.incrementAndGet()),
                new InMemoryRequirementDeliveryJobStore(), SnowflakeIdGenerator.defaultGenerator(), null,
                "test-worker", 3, 60_000L, heartbeatScheduler, null, commands,
                command -> { throw new AssertionError("resume must not use the generic executor"); }, null,
                RequirementDeliverySchedulingPolicy.defaults(), null);
        dispatcher.setRequirementPolicyTransactionPort(new RecordingPolicyTransactionPort(
                new RequirementPolicyResumeResult(appliedPolicyRun("approval-heartbeat", resume, 11L, 21L, now),
                        resume.succeeded(now), next, 11L, 21L),
                heartbeatScheduler::runCapturedTaskEvenIfCancelled));

        CompletableFuture<RequirementDeliveryResult> future = new CompletableFuture<>();
        invokeRunClaimedCommand(dispatcher, resume, future);

        assertFalse(future.isCompletedExceptionally());
        assertEquals(0, commands.failedCalls.get(), "a terminal heartbeat must not retry an already-consumed command");
        assertEquals(1, scheduled.get());
        assertEquals(1L, dispatcher.metricsSnapshot().completed());
    }

    @Test
    void leaseLostBeforeApprovalResumeIsObservedWithoutCallingThePolicyConsumer() {
        long now = System.currentTimeMillis();
        HeartbeatRacingStageCommandStore commands = new HeartbeatRacingStageCommandStore();
        RequirementStageCommand resume = approvalResumeCommand("approval-resume-lease-lost", "approval-lease", 10L, 20L)
                .claimed("test-worker", now + 60_000L, now);
        RequirementStageCommand next = RequirementStageCommand.pending(
                "approval-lease-first-role", "approval-lease", 11L, 21L,
                AgentRole.REQUIREMENT_REVIEWER.name(),
                "ROLE_EXECUTION:" + AgentRole.REQUIREMENT_REVIEWER.name(),
                0, 3, now + 1_000L, ScheduleResourceClass.PROVIDER,
                Set.of(ScheduleResourceClass.PROVIDER), "project-1", "provider", "P1",
                resume.policyRunId(), now);
        commands.enqueue(next);
        TaskScheduler heartbeatScheduler = mock(TaskScheduler.class);
        when(heartbeatScheduler.scheduleAtFixedRate(any(Runnable.class), any(Duration.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return mock(ScheduledFuture.class);
                });
        RecordingPolicyTransactionPort policyPort = new RecordingPolicyTransactionPort(
                new RequirementPolicyResumeResult(appliedPolicyRun("approval-lease", resume, 11L, 21L, now),
                        resume.succeeded(now), next, 11L, 21L));
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                null, new TaskExecutorAdapter(new SyncTaskExecutor()),
                new InMemoryRequirementDeliveryJobStore(), SnowflakeIdGenerator.defaultGenerator(), null,
                "test-worker", 3, 60_000L, heartbeatScheduler, null, commands,
                command -> { throw new AssertionError("resume must not use the generic executor"); }, null,
                RequirementDeliverySchedulingPolicy.defaults(), null);
        dispatcher.setRequirementPolicyTransactionPort(policyPort);

        CompletableFuture<RequirementDeliveryResult> future = new CompletableFuture<>();
        invokeRunClaimedCommand(dispatcher, resume, future);

        assertTrue(future.isCompletedExceptionally());
        assertEquals(1L, dispatcher.metricsSnapshot().leaseLost());
        assertEquals(0, policyPort.consumeCalls.get());
    }

    @Test
    void approvalResumeConsumerFailureOnlyRetriesCurrentCommandWithoutTaskOrJobMutation() {
        long now = 1_784_100_000_000L;
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand pending = approvalResumeCommand("approval-resume-failure", "approval-failure", 10L, 20L);
        commands.enqueue(pending);
        RequirementStageCommand resume = commands.claimBatch("test-worker", now, 60_000L, 1).getFirst();
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        RequirementDeliveryJobStore jobs = mock(RequirementDeliveryJobStore.class);
        RequirementStageExecutor executor = mock(RequirementStageExecutor.class);
        RequirementStageFinalizationPort finalizer = mock(RequirementStageFinalizationPort.class);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()),
                jobs, SnowflakeIdGenerator.defaultGenerator(), registry,
                "test-worker", 3, 60_000L, null, null, commands, executor, finalizer,
                RequirementDeliverySchedulingPolicy.defaults(), null);
        dispatcher.setRequirementPolicyTransactionPort(new RecordingPolicyTransactionPort(new IllegalStateException("stale approval")));

        CompletableFuture<RequirementDeliveryResult> future = new CompletableFuture<>();
        invokeRunClaimedCommand(dispatcher, resume, future);

        assertTrue(future.isCompletedExceptionally());
        assertEquals(RequirementStageCommand.Status.FAILED_RETRYABLE,
                commands.findById(resume.commandId()).orElseThrow().status());
        verifyNoInteractions(registry, executor, finalizer);
        assertNoUmbrellaJobMutation(jobs);
    }

    @Test
    void waitingApprovalSubmitFailsClosedWithoutJobOrGenericPolicyCommand() {
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        when(registry.getTask("waiting-approval-submit")).thenReturn(
                requirementTask("waiting-approval-submit", RdTaskStatus.WAITING_APPROVAL));
        RequirementDeliveryJobStore jobs = mock(RequirementDeliveryJobStore.class);
        RequirementStageCommandStore commands = mock(RequirementStageCommandStore.class);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()), jobs,
                SnowflakeIdGenerator.defaultGenerator(), registry, "test-worker", 3, 60_000L, null, null,
                commands, command -> { throw new AssertionError("waiting approval must not execute"); });

        assertThrows(IllegalStateException.class, () -> dispatcher.submit("waiting-approval-submit"));

        verifyNoInteractions(jobs, commands);
    }

    @Test
    void executingSubmitCannotMintAnUnboundFirstRoleCommand() {
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        when(registry.getTask("executing-without-policy-command")).thenReturn(
                requirementTask("executing-without-policy-command", RdTaskStatus.EXECUTING));
        RequirementDeliveryJobStore jobs = mock(RequirementDeliveryJobStore.class);
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementStageExecutor executor = mock(RequirementStageExecutor.class);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()), jobs,
                SnowflakeIdGenerator.defaultGenerator(), registry, "test-worker", 3, 60_000L, null, null,
                commands, executor);

        assertThrows(IllegalStateException.class,
                () -> dispatcher.submit("executing-without-policy-command"));

        assertTrue(commands.find(
                "executing-without-policy-command", AgentRole.requirementDeliveryOrder().getFirst().name(),
                "ROLE_EXECUTION:" + AgentRole.requirementDeliveryOrder().getFirst().name()).isEmpty());
        verifyNoInteractions(jobs, executor);
    }

    @Test
    void unpreparedPolicyControlRoutesFailClosedInsteadOfMintingGenericCommands() {
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        when(registry.getTask("policy-route-plan")).thenReturn(
                requirementTask("policy-route-plan", RdTaskStatus.PLAN_GENERATED));
        when(registry.getTask("policy-route-apply")).thenReturn(
                requirementTask("policy-route-apply", RdTaskStatus.WAITING_POLICY));
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()),
                new InMemoryRequirementDeliveryJobStore(), SnowflakeIdGenerator.defaultGenerator(), registry,
                "test-worker", 3, 60_000L, null, null, commands,
                command -> { throw new AssertionError("routing preflight must not execute the command"); });

        assertThrows(IllegalStateException.class,
                () -> invokeEnqueueStageCommand(dispatcher, "policy-route-plan"));
        assertThrows(IllegalStateException.class,
                () -> invokeEnqueueStageCommand(dispatcher, "policy-route-apply"));
        assertTrue(commands.find("policy-route-plan", "REQUIREMENT_DELIVERY", "POLICY_EVALUATE").isEmpty());
        assertTrue(commands.find("policy-route-apply", "REQUIREMENT_DELIVERY", "POLICY_APPLY").isEmpty());
    }

    @Test
    void planGeneratedPreparesTheLedgerBeforeMakingItsEvaluateCommandVisible() {
        String taskId = "prepared-policy-route";
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        RdRequirementTask task = requirementTask(taskId, RdTaskStatus.PLAN_GENERATED);
        when(registry.getTask(taskId)).thenReturn(task);
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        String plan = "{\"plan\":true}";
        String policy = "{\"policyAction\":\"ALLOWED\",\"reason\":\"safe\"}";
        RequirementPolicyEvaluationProposal proposal = new RequirementPolicyEvaluationProposal(
                taskId, task.version(), task.fencingToken(), plan, RequirementPolicyRun.canonicalJsonDigest(plan),
                policy, RequirementPolicyRun.canonicalJsonDigest(policy), "ALLOWED");
        when(engine.evaluateFrozenPolicy(any())).thenReturn(proposal);
        RequirementPolicyTransactionPort policyPort = mock(RequirementPolicyTransactionPort.class);
        InMemoryRequirementPolicyRunStore policyStore = new InMemoryRequirementPolicyRunStore();
        when(policyPort.prepareEvaluation(any(), any(), anyLong())).thenAnswer(invocation -> {
            RequirementStageCommand command = invocation.getArgument(1);
            RequirementPolicyEvaluationProposal received = invocation.getArgument(0);
            RequirementPolicyRun ready = new RequirementPolicyRun(
                    command.policyRunId(), taskId, task.version(), task.fencingToken(),
                    received.planJson(), received.planDigest(), "", "", "", RequirementPolicyRunState.PLAN_READY,
                    task.version(), task.fencingToken(), null, null, "", "", "", 0L,
                    "", "", 0L, 0L, 1L, 1L);
            policyStore.createOrGet(ready);
            commands.enqueue(command);
            return new RequirementPolicyEvaluationPreparationResult(ready, command);
        });
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine, new TaskExecutorAdapter(new SyncTaskExecutor()), new InMemoryRequirementDeliveryJobStore(),
                SnowflakeIdGenerator.defaultGenerator(), registry, "test-worker", 3, 60_000L,
                null, null, commands, command -> { throw new AssertionError("not claimed in enqueue test"); });
        dispatcher.setRequirementPolicyTransactionPort(policyPort);
        dispatcher.setRequirementPolicyRunStore(policyStore);

        RequirementStageCommand evaluate = invokeEnqueueStageCommand(dispatcher, taskId);

        assertEquals("POLICY_EVALUATE", evaluate.stage());
        assertEquals(evaluate.commandId(), evaluate.policyRunId());
        assertEquals(evaluate, commands.findById(evaluate.commandId()).orElseThrow());
        verify(engine).evaluateFrozenPolicy(evaluate);
        verify(policyPort).prepareEvaluation(org.mockito.ArgumentMatchers.eq(proposal),
                org.mockito.ArgumentMatchers.eq(evaluate), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void waitingPolicyWithoutTheRecordedApplyCommandFailsClosedAndDoesNotMintAnOrphan() {
        String taskId = "waiting-policy-no-apply";
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        RdRequirementTask task = requirementTask(taskId, RdTaskStatus.WAITING_POLICY);
        when(registry.getTask(taskId)).thenReturn(task);
        String plan = "{\"plan\":true}";
        String policy = "{\"policyAction\":\"ALLOWED\",\"reason\":\"safe\"}";
        InMemoryRequirementPolicyRunStore policyStore = new InMemoryRequirementPolicyRunStore();
        RequirementPolicyRun decided = new RequirementPolicyRun(
                "policy-no-apply", taskId, task.version() - 1L, task.fencingToken() - 1L,
                plan, RequirementPolicyRun.canonicalJsonDigest(plan),
                "", "", "", RequirementPolicyRunState.PLAN_READY,
                task.version() - 1L, task.fencingToken() - 1L, null, null, "", "", "", 0L,
                "", "", 0L, 0L, 1L, 1L);
        policyStore.createOrGet(decided);
        policyStore.compareAndSet(new RequirementPolicyRun(
                decided.id(), decided.taskId(), decided.sourceTaskVersion(), decided.sourceFencingToken(),
                decided.planJson(), decided.planDigest(),
                policy, RequirementPolicyRun.canonicalJsonDigest(policy), "ALLOWED",
                RequirementPolicyRunState.POLICY_DECIDED, task.version(), task.fencingToken(),
                null, null, "", "", "", 0L, "", "", 0L, decided.ledgerVersion() + 1L,
                decided.createdAtEpochMillis(), decided.updatedAtEpochMillis() + 1L),
                RequirementPolicyRunState.PLAN_READY, decided.ledgerVersion());
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()),
                new InMemoryRequirementDeliveryJobStore(), SnowflakeIdGenerator.defaultGenerator(), registry,
                "test-worker", 3, 60_000L, null, null, commands,
                command -> { throw new AssertionError("orphan apply must never execute"); });
        dispatcher.setRequirementPolicyRunStore(policyStore);

        assertThrows(IllegalStateException.class, () -> dispatcher.submit(taskId));

        assertTrue(commands.find(taskId, "REQUIREMENT_DELIVERY", "POLICY_APPLY").isEmpty());
    }

    @Test
    void unpreparedDedicatedPolicyControlDoesNotCreateAnUmbrellaJob() {
        for (RdTaskStatus status : List.of(RdTaskStatus.PLAN_GENERATED, RdTaskStatus.WAITING_POLICY)) {
            String taskId = "policy-submit-" + status;
            RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
            when(registry.getTask(taskId)).thenReturn(requirementTask(taskId, status));
            RequirementDeliveryJobStore jobs = mock(RequirementDeliveryJobStore.class);
            InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
            RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                    mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(ignored -> { }), jobs,
                    SnowflakeIdGenerator.defaultGenerator(), registry, "test-worker", 3, 60_000L,
                    null, null, commands, mock(RequirementStageExecutor.class));

            assertThrows(IllegalStateException.class, () -> dispatcher.submit(taskId));

            verify(jobs, never()).enqueue(any());
        }
    }

    @Test
    void corruptPolicyControlRolesFailClosedBeforeGenericJobExecutorOrFinalizer() {
        for (String stage : List.of("POLICY_EVALUATE", "POLICY_APPLY")) {
            long now = System.currentTimeMillis();
            InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
            RequirementStageCommand pending = RequirementStageCommand.pending(
                    "corrupt-" + stage, "corrupt-policy-" + stage, 10L, 20L,
                    "CORRUPT_ROLE", stage, 0, 3, now + 60_000L,
                    ScheduleResourceClass.GENERIC, "project-1", "", "P1", now);
            commands.enqueue(pending);
            RequirementStageCommand claimed = commands.claimBatch("test-worker", now, 60_000L, 1).getFirst();
            RequirementDeliveryJobStore jobs = mock(RequirementDeliveryJobStore.class);
            RequirementStageExecutor executor = mock(RequirementStageExecutor.class);
            RequirementStageFinalizationPort finalizer = mock(RequirementStageFinalizationPort.class);
            RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                    mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()),
                    jobs, SnowflakeIdGenerator.defaultGenerator(), mock(RagStreamTaskRegistry.class),
                    "test-worker", 3, 60_000L, null, null, commands, executor, finalizer,
                    RequirementDeliverySchedulingPolicy.defaults(), null);

            CompletableFuture<RequirementDeliveryResult> future = new CompletableFuture<>();
            invokeRunClaimedCommand(dispatcher, claimed, future);

            assertTrue(future.isCompletedExceptionally());
            assertEquals(RequirementStageCommand.Status.FAILED_RETRYABLE,
                    commands.findById(claimed.commandId()).orElseThrow().status());
            verifyNoInteractions(executor, finalizer);
            assertNoUmbrellaJobMutation(jobs);
        }
    }

    @Test
    void controlCommandExecutorRejectionAtMaxAttemptsDoesNotDeadLetterTheTask() {
        for (String stage : List.of("POLICY", "POLICY_EVALUATE", "POLICY_APPLY", "APPROVAL_RESUME")) {
            long now = System.currentTimeMillis();
            InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
            RequirementStageCommand pending = RequirementStageCommand.pending(
                    "reject-" + stage, "reject-task-" + stage, 10L, 20L,
                    "REQUIREMENT_DELIVERY", stage, 0, 1, now + 60_000L,
                    ScheduleResourceClass.GENERIC, Set.of(ScheduleResourceClass.GENERIC),
                    "project-1", "provider", "P1", now);
            commands.enqueue(pending);
            RequirementStageCommand claimed = commands.claimBatch("test-worker", now, 60_000L, 1).getFirst();
            RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
            RequirementDeliveryJobStore jobs = mock(RequirementDeliveryJobStore.class);
            RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                    mock(RequirementDeliveryEngine.class),
                    new TaskExecutorAdapter(ignored -> { throw new TaskRejectedException("saturated"); }),
                    jobs, SnowflakeIdGenerator.defaultGenerator(), registry, "test-worker", 1, 60_000L,
                    null, null, commands, mock(RequirementStageExecutor.class));

            invokeScheduleClaimedStageCommand(dispatcher, claimed, new CompletableFuture<>());

            assertEquals(RequirementStageCommand.Status.DEAD_LETTERED,
                    commands.findById(claimed.commandId()).orElseThrow().status());
            verifyNoInteractions(registry);
            assertNoUmbrellaJobMutation(jobs);
        }
    }

    @Test
    void expiredControlCommandAtMaxAttemptsDoesNotDeadLetterTheTaskDuringRecovery() {
        for (String stage : List.of("POLICY", "POLICY_EVALUATE", "POLICY_APPLY", "APPROVAL_RESUME")) {
            long past = System.currentTimeMillis() - 10_000L;
            InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
            RequirementStageCommand pending = RequirementStageCommand.pending(
                    "expired-" + stage, "expired-task-" + stage, 10L, 20L,
                    "REQUIREMENT_DELIVERY", stage, 0, 1, past + 1L,
                    ScheduleResourceClass.GENERIC, Set.of(ScheduleResourceClass.GENERIC),
                    "project-1", "provider", "P1", past);
            commands.enqueue(pending);
            commands.claimBatch("dead-worker", past, 1L, 1);
            RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
            RequirementDeliveryJobStore jobs = mock(RequirementDeliveryJobStore.class);
            RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                    mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()),
                    jobs, SnowflakeIdGenerator.defaultGenerator(), registry, "test-worker", 1, 60_000L,
                    null, null, commands, mock(RequirementStageExecutor.class));

            dispatcher.recover();

            assertEquals(RequirementStageCommand.Status.DEAD_LETTERED,
                    commands.findById(pending.commandId()).orElseThrow().status());
            verifyNoInteractions(registry);
            assertNoUmbrellaJobMutation(jobs);
        }
    }

    @Test
    void legacyPolicyCommandRecoveryFailsClosedWithoutGenericExecutionOrTaskMutation() {
        long now = System.currentTimeMillis();
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand legacy = RequirementStageCommand.pending(
                "legacy-policy", "legacy-policy-task", 10L, 20L,
                "REQUIREMENT_DELIVERY", "POLICY", 0, 3, now + 60_000L,
                ScheduleResourceClass.GENERIC, Set.of(ScheduleResourceClass.GENERIC),
                "project-1", "provider", "P1", now);
        commands.enqueue(legacy);
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        RequirementStageExecutor stageExecutor = mock(RequirementStageExecutor.class);
        RequirementStageFinalizationPort finalizer = mock(RequirementStageFinalizationPort.class);
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        RequirementDeliveryJobStore jobs = mock(RequirementDeliveryJobStore.class);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine, new TaskExecutorAdapter(new SyncTaskExecutor()), jobs,
                SnowflakeIdGenerator.defaultGenerator(), registry, "test-worker", 3, 60_000L,
                null, null, commands, stageExecutor, finalizer,
                RequirementDeliverySchedulingPolicy.defaults(), null);

        dispatcher.recover();

        assertEquals(RequirementStageCommand.Status.FAILED_RETRYABLE,
                commands.findById(legacy.commandId()).orElseThrow().status());
        verifyNoInteractions(engine, stageExecutor, finalizer, registry);
        assertNoUmbrellaJobMutation(jobs);
    }

    @Test
    void policyEvaluateCreatesDeterministicLedgerRecordsDecisionAndSchedulesDurableApply() {
        long now = System.currentTimeMillis();
        String taskId = "policy-evaluate-success";
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand pending = policyControlCommand(
                "71001", taskId, 10L, 20L, "POLICY_EVALUATE", now);
        commands.enqueue(pending);
        RequirementStageCommand evaluate = commands.claimBatch("test-worker", now, 60_000L, 1).getFirst();
        String plan = RequirementPolicyRun.canonicalizeJson(
                "{\"taskId\":\"" + taskId + "\",\"implementationSteps\":[\"frozen\"],"
                        + "\"acceptanceCriteria\":[],\"suggestedValidationCommands\":[]}");
        String policy = RequirementPolicyRun.canonicalizeJson(
                "{\"policyAction\":\"ALLOWED\",\"riskLevel\":\"LOW\",\"reason\":\"ok\"}");
        RequirementPolicyEvaluationProposal proposal = new RequirementPolicyEvaluationProposal(
                taskId, 10L, 20L, plan, RequirementPolicyRun.canonicalJsonDigest(plan),
                policy, RequirementPolicyRun.canonicalJsonDigest(policy), "ALLOWED");
        RequirementPolicyRun ready = policyRun(
                evaluate.commandId(), taskId, plan, "", "", RequirementPolicyRunState.PLAN_READY,
                10L, 20L, 10L, 20L, "", 0L, 0L, now);
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        when(engine.evaluateFrozenPolicy(evaluate)).thenReturn(proposal);
        RequirementStageCommand apply = policyControlCommand(
                "71002", taskId, 11L, 21L, "POLICY_APPLY", evaluate.commandId(), now + 1L);
        commands.enqueue(apply);
        RequirementPolicyRun decided = policyRun(
                evaluate.commandId(), taskId, plan, policy, "ALLOWED",
                RequirementPolicyRunState.POLICY_DECIDED, 10L, 20L, 11L, 21L,
                "", 0L, 1L, now);
        RequirementPolicyEvaluationResult recorded = new RequirementPolicyEvaluationResult(
                decided, evaluate.succeeded(now + 1L), apply, 11L, 21L);
        InMemoryRequirementPolicyRunStore policyStore = new InMemoryRequirementPolicyRunStore();
        policyStore.createOrGet(ready);
        RecordingPolicyControlPort policyPort = new RecordingPolicyControlPort(recorded, null);
        RequirementDeliveryJobStore jobs = mock(RequirementDeliveryJobStore.class);
        RequirementStageExecutor generic = mock(RequirementStageExecutor.class);
        RequirementStageFinalizationPort finalizer = mock(RequirementStageFinalizationPort.class);
        AtomicInteger scheduled = new AtomicInteger();
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine, new TaskExecutorAdapter(ignored -> scheduled.incrementAndGet()), jobs,
                SnowflakeIdGenerator.defaultGenerator(), mock(RagStreamTaskRegistry.class),
                "test-worker", 3, 60_000L, null, null, commands, generic, finalizer,
                RequirementDeliverySchedulingPolicy.defaults(), null);
        dispatcher.setRequirementPolicyRunStore(policyStore);
        dispatcher.setRequirementPolicyTransactionPort(policyPort);

        invokeRunClaimedCommand(dispatcher, evaluate, new CompletableFuture<>());

        assertEquals(1, policyPort.evaluationCalls.get());
        assertEquals(evaluate.commandId(), policyPort.lastDecision.policyRunId());
        assertEquals(RequirementPolicyRunState.PLAN_READY,
                policyStore.findById(evaluate.commandId()).orElseThrow().state());
        assertEquals(1, scheduled.get());
        verify(engine).evaluateFrozenPolicy(evaluate);
        verifyNoInteractions(generic, finalizer);
        assertNoUmbrellaJobMutation(jobs);
    }

    @Test
    void policyApplyReturnsStableWaitingOrDeniedResultWithoutGenericContinuation() throws Exception {
        for (RequirementPolicyApplyDisposition disposition : List.of(
                RequirementPolicyApplyDisposition.WAITING_APPROVAL,
                RequirementPolicyApplyDisposition.DENIED)) {
            long now = System.currentTimeMillis();
            String taskId = "policy-apply-" + disposition;
            InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
            RequirementStageCommand pending = policyControlCommand(
                    "72" + disposition.ordinal() + "01", taskId, 11L, 21L, "POLICY_APPLY", now);
            commands.enqueue(pending);
            RequirementStageCommand apply = commands.claimBatch("test-worker", now, 60_000L, 1).getFirst();
            String plan = RequirementPolicyRun.canonicalizeJson(
                    "{\"taskId\":\"" + taskId + "\",\"implementationSteps\":[],"
                            + "\"acceptanceCriteria\":[],\"suggestedValidationCommands\":[]}");
            String action = disposition == RequirementPolicyApplyDisposition.WAITING_APPROVAL
                    ? "WAITING_APPROVAL" : "UNSAFE";
            String policy = RequirementPolicyRun.canonicalizeJson(
                    "{\"policyAction\":\"" + action + "\",\"riskLevel\":\"HIGH\",\"reason\":\"blocked\"}");
            RequirementPolicyRunState state = disposition == RequirementPolicyApplyDisposition.WAITING_APPROVAL
                    ? RequirementPolicyRunState.WAITING_APPROVAL : RequirementPolicyRunState.DENIED;
            RequirementPolicyRun ledger = policyRun(
                    "policy-" + taskId, taskId, plan, policy, action, state,
                    10L, 20L, 12L, 22L,
                    disposition == RequirementPolicyApplyDisposition.DENIED ? apply.commandId() : "",
                    disposition == RequirementPolicyApplyDisposition.DENIED ? now + 1L : 0L, 2L, now);
            RequirementPolicyApplyResult result = new RequirementPolicyApplyResult(
                    ledger, apply.succeeded(now + 1L), disposition, null, 12L, 22L);
            RecordingPolicyControlPort policyPort = new RecordingPolicyControlPort(null, result);
            RequirementDeliveryJobStore jobs = mock(RequirementDeliveryJobStore.class);
            RequirementStageExecutor generic = mock(RequirementStageExecutor.class);
            RequirementStageFinalizationPort finalizer = mock(RequirementStageFinalizationPort.class);
            RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                    mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()), jobs,
                    SnowflakeIdGenerator.defaultGenerator(), mock(RagStreamTaskRegistry.class),
                    "test-worker", 3, 60_000L, null, null, commands, generic, finalizer,
                    RequirementDeliverySchedulingPolicy.defaults(), null);
            dispatcher.setRequirementPolicyTransactionPort(policyPort);
            CompletableFuture<RequirementDeliveryResult> future = new CompletableFuture<>();

            invokeRunClaimedCommand(dispatcher, apply, future);

            RequirementDeliveryResult completed = future.get();
            assertEquals(disposition == RequirementPolicyApplyDisposition.WAITING_APPROVAL
                    ? RdTaskStatus.WAITING_APPROVAL : RdTaskStatus.FAILED_NEEDS_HUMAN, completed.status());
            assertEquals(disposition == RequirementPolicyApplyDisposition.DENIED ? "blocked" : "",
                    completed.errorMessage());
            assertEquals(1, policyPort.applyCalls.get());
            verifyNoInteractions(generic, finalizer);
            assertNoUmbrellaJobMutation(jobs);
        }
    }

    @Test
    void decidedPolicyEvaluationReplayDoesNotRunTheGateAgain() {
        long now = System.currentTimeMillis();
        String taskId = "policy-evaluate-replay";
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand pending = policyControlCommand(
                "73001", taskId, 10L, 20L, "POLICY_EVALUATE", now);
        commands.enqueue(pending);
        RequirementStageCommand evaluate = commands.claimBatch("test-worker", now, 60_000L, 1).getFirst();
        RequirementStageCommand apply = policyControlCommand(
                "73002", taskId, 11L, 21L, "POLICY_APPLY", evaluate.commandId(), now + 1L);
        commands.enqueue(apply);
        String plan = RequirementPolicyRun.canonicalizeJson(
                "{\"taskId\":\"" + taskId + "\",\"implementationSteps\":[],"
                        + "\"acceptanceCriteria\":[],\"suggestedValidationCommands\":[]}");
        String policy = RequirementPolicyRun.canonicalizeJson(
                "{\"policyAction\":\"ALLOWED\",\"riskLevel\":\"LOW\",\"reason\":\"ok\"}");
        RequirementPolicyRun ready = policyRun(
                evaluate.commandId(), taskId, plan, "", "", RequirementPolicyRunState.PLAN_READY,
                10L, 20L, 10L, 20L, "", 0L, 0L, now);
        RequirementPolicyRun decided = policyRun(
                evaluate.commandId(), taskId, plan, policy, "ALLOWED",
                RequirementPolicyRunState.POLICY_DECIDED, 10L, 20L, 11L, 21L,
                "", 0L, 1L, now);
        InMemoryRequirementPolicyRunStore policyStore = new InMemoryRequirementPolicyRunStore();
        policyStore.createOrGet(ready);
        policyStore.compareAndSet(decided, RequirementPolicyRunState.PLAN_READY, 0L);
        RecordingPolicyControlPort policyPort = new RecordingPolicyControlPort(
                new RequirementPolicyEvaluationResult(
                        decided, evaluate.succeeded(now + 1L), apply, 11L, 21L), null);
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        AtomicInteger scheduled = new AtomicInteger();
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine, new TaskExecutorAdapter(ignored -> scheduled.incrementAndGet()),
                new InMemoryRequirementDeliveryJobStore(), SnowflakeIdGenerator.defaultGenerator(),
                mock(RagStreamTaskRegistry.class), "test-worker", 3, 60_000L, null, null,
                commands, mock(RequirementStageExecutor.class), mock(RequirementStageFinalizationPort.class),
                RequirementDeliverySchedulingPolicy.defaults(), null);
        dispatcher.setRequirementPolicyRunStore(policyStore);
        dispatcher.setRequirementPolicyTransactionPort(policyPort);

        invokeRunClaimedCommand(dispatcher, evaluate, new CompletableFuture<>());

        assertEquals(1, policyPort.evaluationCalls.get());
        assertEquals(1, scheduled.get());
        verify(engine, never()).evaluateFrozenPolicy(any());
    }

    @Test
    void allowedPolicyApplySchedulesOnlyItsDurableFirstRole() {
        long now = System.currentTimeMillis();
        String taskId = "policy-apply-allowed";
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand pending = policyControlCommand(
                "74001", taskId, 11L, 21L, "POLICY_APPLY", now);
        commands.enqueue(pending);
        RequirementStageCommand apply = commands.claimBatch("test-worker", now, 60_000L, 1).getFirst();
        String firstRole = AgentRole.requirementDeliveryOrder().getFirst().name();
        String policyRunId = "policy-" + taskId;
        RequirementStageCommand next = RequirementStageCommand.pending(
                "74002", taskId, 12L, 22L, firstRole, "ROLE_EXECUTION:" + firstRole,
                0, 3, now + 60_000L, ScheduleResourceClass.PROVIDER,
                Set.of(ScheduleResourceClass.PROVIDER), "project-1", "provider", "P1",
                policyRunId, now + 1L);
        commands.enqueue(next);
        String plan = RequirementPolicyRun.canonicalizeJson(
                "{\"taskId\":\"" + taskId + "\",\"implementationSteps\":[],"
                        + "\"acceptanceCriteria\":[],\"suggestedValidationCommands\":[]}");
        String policy = RequirementPolicyRun.canonicalizeJson(
                "{\"policyAction\":\"ALLOWED\",\"riskLevel\":\"LOW\",\"reason\":\"ok\"}");
        RequirementPolicyRun ledger = policyRun(
                policyRunId, taskId, plan, policy, "ALLOWED", RequirementPolicyRunState.APPLIED,
                10L, 20L, 12L, 22L, apply.commandId(), now + 1L, 2L, now);
        RequirementPolicyApplyResult result = new RequirementPolicyApplyResult(
                ledger, apply.succeeded(now + 1L), RequirementPolicyApplyDisposition.ALLOWED,
                next, 12L, 22L);
        RecordingPolicyControlPort policyPort = new RecordingPolicyControlPort(null, result);
        RequirementDeliveryJobStore jobs = mock(RequirementDeliveryJobStore.class);
        RequirementStageExecutor generic = mock(RequirementStageExecutor.class);
        RequirementStageFinalizationPort finalizer = mock(RequirementStageFinalizationPort.class);
        AtomicInteger scheduled = new AtomicInteger();
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class),
                new TaskExecutorAdapter(ignored -> scheduled.incrementAndGet()), jobs,
                SnowflakeIdGenerator.defaultGenerator(), mock(RagStreamTaskRegistry.class),
                "test-worker", 3, 60_000L, null, null, commands, generic, finalizer,
                RequirementDeliverySchedulingPolicy.defaults(), null);
        dispatcher.setRequirementPolicyTransactionPort(policyPort);
        CompletableFuture<RequirementDeliveryResult> future = new CompletableFuture<>();

        invokeRunClaimedCommand(dispatcher, apply, future);

        assertEquals(1, policyPort.applyCalls.get());
        assertEquals(1, scheduled.get());
        assertEquals(policyRunId, next.policyRunId());
        assertFalse(future.isDone());
        verifyNoInteractions(generic, finalizer);
        assertNoUmbrellaJobMutation(jobs);
    }

    @Test
    void approvalResumeWithoutTransactionPortFailsClosedBeforeGenericExecution() {
        long now = 1_784_100_000_000L;
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand pending = approvalResumeCommand("approval-resume-port-missing", "approval-port-missing", 10L, 20L);
        commands.enqueue(pending);
        RequirementStageCommand resume = commands.claimBatch("test-worker", now, 60_000L, 1).getFirst();
        RequirementDeliveryJobStore jobs = mock(RequirementDeliveryJobStore.class);
        RequirementStageExecutor executor = mock(RequirementStageExecutor.class);
        RequirementStageFinalizationPort finalizer = mock(RequirementStageFinalizationPort.class);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()),
                jobs, SnowflakeIdGenerator.defaultGenerator(), mock(RagStreamTaskRegistry.class),
                "test-worker", 3, 60_000L, null, null, commands, executor, finalizer,
                RequirementDeliverySchedulingPolicy.defaults(), null);

        CompletableFuture<RequirementDeliveryResult> future = new CompletableFuture<>();
        invokeRunClaimedCommand(dispatcher, resume, future);

        assertTrue(future.isCompletedExceptionally());
        assertEquals(RequirementStageCommand.Status.FAILED_RETRYABLE,
                commands.findById(resume.commandId()).orElseThrow().status());
        verifyNoInteractions(executor, finalizer);
        assertNoUmbrellaJobMutation(jobs);
    }

    @Test
    void approvalResumeWithCorruptRoleStillFailsClosedOutsideGenericExecution() {
        long now = System.currentTimeMillis();
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand pending = RequirementStageCommand.pending(
                "approval-resume-wrong-role", "approval-wrong-role", 10L, 20L,
                "CORRUPT_ROLE", "APPROVAL_RESUME", 0, 3, now + 60_000L,
                ScheduleResourceClass.GENERIC, "project-1", "", "P1", now);
        commands.enqueue(pending);
        RequirementStageCommand claimed = commands.claimBatch("test-worker", now, 60_000L, 1).getFirst();
        RequirementDeliveryJobStore jobs = mock(RequirementDeliveryJobStore.class);
        RequirementStageExecutor executor = mock(RequirementStageExecutor.class);
        RequirementStageFinalizationPort finalizer = mock(RequirementStageFinalizationPort.class);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()),
                jobs, SnowflakeIdGenerator.defaultGenerator(), mock(RagStreamTaskRegistry.class),
                "test-worker", 3, 60_000L, null, null, commands, executor, finalizer,
                RequirementDeliverySchedulingPolicy.defaults(), null);
        dispatcher.setRequirementPolicyTransactionPort(
                new RecordingPolicyTransactionPort(new IllegalStateException("corrupt resume identity")));

        CompletableFuture<RequirementDeliveryResult> future = new CompletableFuture<>();
        invokeRunClaimedCommand(dispatcher, claimed, future);

        assertTrue(future.isCompletedExceptionally());
        assertEquals(RequirementStageCommand.Status.FAILED_RETRYABLE,
                commands.findById(claimed.commandId()).orElseThrow().status());
        verifyNoInteractions(executor, finalizer);
        assertNoUmbrellaJobMutation(jobs);
    }

    @Test
    void zeroFenceTaskSnapshotCannotCreateAStageCommand() {
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        RdRequirementTask persisted = requirementTask("zero-fence-dispatch", RdTaskStatus.CREATED);
        RdRequirementTask legacySnapshot = persisted.withConcurrency(persisted.version(), 0L);
        when(registry.getTask("zero-fence-dispatch")).thenReturn(legacySnapshot);
        RequirementStageCommandStore commands = mock(RequirementStageCommandStore.class);
        RequirementDeliveryJobStore jobs = mock(RequirementDeliveryJobStore.class);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()),
                jobs, SnowflakeIdGenerator.defaultGenerator(), registry,
                "test-worker", 3, 60_000L, null, null, commands, command -> {
                    throw new AssertionError("stage command must not run");
                });

        assertThrows(IllegalStateException.class, () -> dispatcher.submit("zero-fence-dispatch"));

        verifyNoInteractions(commands);
        verifyNoInteractions(jobs);
    }

    @Test
    void submitWithoutAuthoritativeTaskRegistryLeavesNoJobOrStageCommand() {
        RequirementDeliveryJobStore jobs = mock(RequirementDeliveryJobStore.class);
        RequirementStageCommandStore commands = mock(RequirementStageCommandStore.class);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()), jobs,
                SnowflakeIdGenerator.defaultGenerator(), null, "test-worker", 3, 60_000L, null, null,
                commands, command -> {
                    throw new AssertionError("stage command must not run");
                });

        assertThrows(IllegalStateException.class, () -> dispatcher.submit("missing-registry"));

        verifyNoInteractions(jobs, commands);
    }

    @Test
    void staleExistingInitialCommandCannotCreateAnUmbrellaJob() {
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        when(registry.getTask("initial-stale")).thenReturn(
                requirementTask("initial-stale", RdTaskStatus.CREATED).withConcurrency(7L, 13L));
        RequirementStageCommandStore commands = mock(RequirementStageCommandStore.class);
        when(commands.find("initial-stale", "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING")).thenReturn(
                Optional.of(RequirementStageCommand.pending(
                        "stale-initial", "initial-stale", 6L, 12L, "REQUIREMENT_DELIVERY",
                        "MATERIAL_COLLECTING", 0, 3, 0L, ScheduleResourceClass.GENERIC,
                        "project", "", "P1", 1L)));
        RequirementDeliveryJobStore jobs = mock(RequirementDeliveryJobStore.class);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()), jobs,
                SnowflakeIdGenerator.defaultGenerator(), registry, "test-worker", 3, 60_000L, null, null,
                commands, command -> {
                    throw new AssertionError("stage command must not run");
                });

        assertThrows(IllegalStateException.class, () -> dispatcher.submit("initial-stale"));

        verifyNoInteractions(jobs);
        verify(commands, never()).enqueue(any());
    }

    @Test
    void conflictingEffectiveInitialCommandCannotCreateAnUmbrellaJob() {
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        RdRequirementTask task = requirementTask("initial-enqueue-conflict", RdTaskStatus.CREATED)
                .withConcurrency(7L, 13L);
        when(registry.getTask("initial-enqueue-conflict")).thenReturn(task);
        RequirementStageCommandStore commands = mock(RequirementStageCommandStore.class);
        when(commands.find("initial-enqueue-conflict", "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING"))
                .thenReturn(Optional.empty());
        when(commands.enqueue(any())).thenAnswer(invocation -> {
            RequirementStageCommand requested = invocation.getArgument(0, RequirementStageCommand.class);
            return new RequirementStageCommand(
                    requested.commandId(), requested.taskId(), requested.taskVersion() + 1L,
                    requested.fencingToken(), requested.role(), requested.stage(), requested.attemptNo(),
                    requested.maxAttempts(), requested.deadlineEpochMillis(), requested.resourceClass(),
                    requested.resourceRequirements(), requested.projectId(), requested.providerId(),
                    requested.priorityRank(), requested.status(), requested.leaseOwner(),
                    requested.leaseUntilEpochMillis(), requested.nextVisibleAtEpochMillis(), requested.lastError(),
                    requested.createdAtEpochMillis(), requested.updatedAtEpochMillis());
        });
        RequirementDeliveryJobStore jobs = mock(RequirementDeliveryJobStore.class);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()), jobs,
                SnowflakeIdGenerator.defaultGenerator(), registry, "test-worker", 3, 60_000L, null, null,
                commands, command -> {
                    throw new AssertionError("stage command must not run");
                });

        assertThrows(IllegalStateException.class, () -> dispatcher.submit("initial-enqueue-conflict"));

        verify(commands).enqueue(any(RequirementStageCommand.class));
        verifyNoInteractions(jobs);
    }

    @Test
    void preparedMutationWithWrongFenceIsNotAcceptedAsAlreadyApplied() {
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        RdRequirementTask current = requirementTask("prepared-wrong-fence", RdTaskStatus.MATERIAL_COLLECTING)
                .withConcurrency(11L, 0L);
        when(registry.getTask("prepared-wrong-fence")).thenReturn(current);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()),
                mock(RequirementDeliveryJobStore.class), SnowflakeIdGenerator.defaultGenerator(), registry,
                "test-worker", 3, 60_000L, null, null, mock(RequirementStageCommandStore.class),
                command -> { throw new AssertionError("stage command must not run"); });
        RequirementStageCommand command = RequirementStageCommand.pending(
                "prepared-wrong-fence-command", "prepared-wrong-fence", 10L, 20L,
                "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING", 0, 3, 0L,
                ScheduleResourceClass.GENERIC, "project-a", "", "P1", 1L);

        assertEquals(null, invokeAlreadyAppliedMutation(
                dispatcher, com.wish.rd.engine.requirement.job.model.RequirementStageFinalization.prepared(
                        command, RdTaskStatus.CREATED, 1L)));
    }

    @Test
    void wrongFencePreparedRecoveryDoesNotFinalizeOrEnqueueAContinuation() {
        String taskId = "prepared-recovery-wrong-fence";
        RequirementStageCommand command = RequirementStageCommand.pending(
                "prepared-recovery-command", taskId, 10L, 20L,
                "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING", 0, 3, 0L,
                ScheduleResourceClass.GENERIC, "project-a", "", "P1", 1L);
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        commands.enqueue(command);
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        when(registry.getTask(taskId)).thenReturn(
                requirementTask(taskId, RdTaskStatus.MATERIAL_COLLECTING).withConcurrency(11L, 0L));
        com.wish.rd.engine.requirement.job.RequirementStageFinalizationPort finalizer = mock(
                com.wish.rd.engine.requirement.job.RequirementStageFinalizationPort.class);
        when(finalizer.findLatestPrepared(command.commandId())).thenReturn(Optional.of(
                com.wish.rd.engine.requirement.job.model.RequirementStageFinalization.prepared(
                        command, RdTaskStatus.CREATED, 1L)));
        AtomicInteger executions = new AtomicInteger();
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()),
                new InMemoryRequirementDeliveryJobStore(), SnowflakeIdGenerator.defaultGenerator(), registry,
                "test-worker", 3, 60_000L, null, null, commands,
                ignored -> {
                    executions.incrementAndGet();
                    return new RequirementDeliveryResult(taskId, RdTaskStatus.MATERIAL_COLLECTING, "", "{}", "");
                },
                finalizer,
                RequirementDeliverySchedulingPolicy.defaults(),
                null);

        dispatcher.recover();

        assertEquals(0, executions.get(), "a mismatched prepared mutation must not run stale stage work");
        assertTrue(commands.find(taskId, "REQUIREMENT_DELIVERY", "MATERIAL_READY").isEmpty());
        verify(finalizer, never()).finalize(any());
    }

    @Test
    void continuationLookupFailureDoesNotFallBackToPreviousMetadata() {
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        RequirementStageCommandStore commands = mock(RequirementStageCommandStore.class);
        when(commands.find("continuation-lookup", "REQUIREMENT_DELIVERY", "MATERIAL_READY"))
                .thenReturn(Optional.empty());
        when(registry.getTask("continuation-lookup")).thenThrow(new IllegalStateException("missing task"));
        RequirementDeliveryDispatchService dispatcher = continuationDispatcher(registry, commands);

        assertThrows(IllegalStateException.class, () -> invokeNewCommand(
                dispatcher, "continuation-lookup", continuationPrevious(), "REQUIREMENT_DELIVERY", "MATERIAL_READY"));

        verify(commands, never()).find("continuation-lookup", "REQUIREMENT_DELIVERY", "MATERIAL_READY");
        verify(commands, never()).enqueue(any());
    }

    @Test
    void staleExistingContinuationCannotBeReusedForANewerTaskSnapshot() {
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        RequirementStageCommandStore commands = mock(RequirementStageCommandStore.class);
        when(registry.getTask("continuation-stale")).thenReturn(
                requirementTask("continuation-stale", RdTaskStatus.MATERIAL_COLLECTING).withConcurrency(7L, 13L));
        when(commands.find("continuation-stale", "REQUIREMENT_DELIVERY", "MATERIAL_READY"))
                .thenReturn(Optional.of(continuationPrevious()));

        assertThrows(IllegalStateException.class, () -> invokeNewCommand(
                continuationDispatcher(registry, commands), "continuation-stale", continuationPrevious(),
                "REQUIREMENT_DELIVERY", "MATERIAL_READY"));

        verify(commands, never()).enqueue(any());

    }

    @Test
    void continuationZeroFenceSnapshotDoesNotCreateACommand() {
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        RequirementStageCommandStore commands = mock(RequirementStageCommandStore.class);
        when(commands.find("continuation-zero", "REQUIREMENT_DELIVERY", "MATERIAL_READY"))
                .thenReturn(Optional.empty());
        when(registry.getTask("continuation-zero")).thenReturn(
                requirementTask("continuation-zero", RdTaskStatus.MATERIAL_COLLECTING).withConcurrency(7L, 0L));
        RequirementDeliveryDispatchService dispatcher = continuationDispatcher(registry, commands);

        assertThrows(IllegalStateException.class, () -> invokeNewCommand(
                dispatcher, "continuation-zero", continuationPrevious(), "REQUIREMENT_DELIVERY", "MATERIAL_READY"));

        verify(commands, never()).enqueue(any());
    }

    @Test
    void continuationUsesTheExactPositiveTaskSnapshotMetadata() {
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        RequirementStageCommandStore commands = mock(RequirementStageCommandStore.class);
        when(commands.find("continuation-positive", "REQUIREMENT_DELIVERY", "MATERIAL_READY"))
                .thenReturn(Optional.empty());
        when(registry.getTask("continuation-positive")).thenReturn(
                requirementTask("continuation-positive", RdTaskStatus.MATERIAL_COLLECTING).withConcurrency(7L, 13L));
        RequirementStageCommand next = invokeNewCommand(
                continuationDispatcher(registry, commands), "continuation-positive", continuationPrevious(),
                "REQUIREMENT_DELIVERY", "MATERIAL_READY");

        assertEquals(7L, next.taskVersion());
        assertEquals(13L, next.fencingToken());
    }

    @Test
    void shouldPersistClaimAndCompletionAroundExecution() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        when(engine.submit("task-1")).thenReturn(new RequirementDeliveryResult(
                "task-1", RdTaskStatus.COMPLETED, "", "{}", ""));
        InMemoryRequirementDeliveryJobStore store = new InMemoryRequirementDeliveryJobStore();
        RequirementDeliveryDispatchService dispatcher = dispatcher(engine, store);

        dispatcher.submit("task-1").join();

        assertEquals(RequirementDeliveryJobStatus.SUCCEEDED, store.findByTask("task-1").orElseThrow().status());
        assertEquals(1, store.findByTask("task-1").orElseThrow().attemptNo());
    }

    @Test
    void shouldPersistAndCompleteAStageCommandAroundExecution() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        when(engine.submit("task-stage")).thenReturn(new RequirementDeliveryResult(
                "task-stage", RdTaskStatus.COMPLETED, "", "{}", ""));
        InMemoryRequirementDeliveryJobStore jobs = new InMemoryRequirementDeliveryJobStore();
        InMemoryRequirementStageCommandStore stages = new InMemoryRequirementStageCommandStore();
        RequirementDeliveryDispatchService dispatcher = dispatcher(engine, jobs, stages);

        dispatcher.submit("task-stage").join();

        assertEquals(1, stages.listByStatus(RequirementStageCommand.Status.SUCCEEDED).size());
    }

    @Test
    void submitUsesNonMutatingStagePlanInsteadOfLegacyExecutor() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        RequirementStageExecutor stageExecutor = mock(RequirementStageExecutor.class);
        when(stageExecutor.plan(any(RequirementStageCommand.class))).thenAnswer(invocation -> {
            RequirementStageCommand command = invocation.getArgument(0);
            return new com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan(
                    1, command.taskId(), command.taskVersion(), command.fencingToken(), RdTaskStatus.CREATED,
                    List.of(com.wish.rd.engine.requirement.job.model.RequirementTaskMutation.statusTransition(
                            RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING, "", "{}", "", "", "")),
                    com.wish.rd.engine.requirement.job.model.CommandDisposition.SUCCEEDED,
                    com.wish.rd.engine.requirement.job.model.ContinuationSpec.terminal(),
                    com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt.none());
        });
        InMemoryRequirementDeliveryJobStore jobs = new InMemoryRequirementDeliveryJobStore();
        InMemoryRequirementStageCommandStore stages = new InMemoryRequirementStageCommandStore();
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        AtomicLong now = new AtomicLong(1_784_100_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
        taskStore.saveRequirementTask(requirementTask("task-bounded", RdTaskStatus.CREATED));
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(taskStore, events, ids);
        com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageFinalizationPort finalizer =
                new com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageFinalizationPort(
                        stages, jobs, taskStore,
                        new com.wish.rd.rag.runtime.impl.CoordinatedRdTaskStatePersistence(taskStore, events),
                        ids);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine, new TaskExecutorAdapter(new SyncTaskExecutor()), jobs, ids, registry,
                "test-worker", 3, 60_000L, null, null, stages, stageExecutor, finalizer,
                RequirementDeliverySchedulingPolicy.defaults(), null);

        dispatcher.submit("task-bounded").join();

        verify(stageExecutor, org.mockito.Mockito.atLeastOnce()).plan(any(RequirementStageCommand.class));
        verify(stageExecutor, never()).execute(any(RequirementStageCommand.class));
        verify(engine, never()).submit("task-bounded");
        assertEquals(1, stages.listByStatus(RequirementStageCommand.Status.SUCCEEDED).size());
    }

    @Test
    void shouldRequeueLeasedStageCommandWhenLocalExecutorRejectsScheduling() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        InMemoryRequirementDeliveryJobStore jobs = new InMemoryRequirementDeliveryJobStore();
        InMemoryRequirementStageCommandStore stages = new InMemoryRequirementStageCommandStore();
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        doThrow(new TaskRejectedException("queue full")).when(executor).execute(any(Runnable.class));
        AtomicLong now = new AtomicLong(1_784_100_000_000L);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine, executor, jobs, new SnowflakeIdGenerator(1, 1, now::getAndIncrement),
                authoritativeRegistry(), "test-worker", 3, 60_000L, null, null, stages);

        assertDoesNotThrow(() -> dispatcher.submit("task-stage-pending"));

        assertEquals(1, stages.listByStatus(RequirementStageCommand.Status.FAILED_RETRYABLE).size());
        assertEquals(1L, dispatcher.metricsSnapshot().queueRejections());
        assertTrue(dispatcher.metricsSnapshot().claimed() >= 1L);
    }

    @Test
    void recoverClaimsDurableStageCommandWithoutAnUmbrellaDeliveryJob() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        RequirementStageExecutor stageExecutor = mock(RequirementStageExecutor.class);
        when(stageExecutor.execute(any(RequirementStageCommand.class))).thenAnswer(invocation -> {
            RequirementStageCommand command = invocation.getArgument(0);
            return new RequirementDeliveryResult(command.taskId(), RdTaskStatus.COMPLETED, "", "{}", "");
        });
        InMemoryRequirementDeliveryJobStore jobs = new InMemoryRequirementDeliveryJobStore();
        InMemoryRequirementStageCommandStore stages = new InMemoryRequirementStageCommandStore();
        long now = System.currentTimeMillis();
        stages.enqueue(RequirementStageCommand.pending(
                "stage-recovery-only", "task-recovery-only", 10L, 20L,
                "REQUIREMENT_DELIVERY", "COMPLETION", 0, 3, 0L,
                com.wish.rd.engine.scheduling.model.ScheduleResourceClass.GENERIC,
                "_default", "", "P2", now));
        RequirementDeliveryDispatchService dispatcher = dispatcher(
                engine, jobs, stages, stageExecutor);

        dispatcher.recover();

        verify(stageExecutor).execute(any(RequirementStageCommand.class));
        verify(engine, never()).submit(any());
        assertEquals(1, stages.listByStatus(RequirementStageCommand.Status.SUCCEEDED).size());
        assertTrue(dispatcher.metricsSnapshot().claimed() >= 1L);
        assertTrue(dispatcher.metricsSnapshot().completed() >= 1L);
    }

    @Test
    void shouldCompleteOriginalSubmitFutureWhenQuotaDefersThenRecoveryClaimsCommand() throws Exception {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        RequirementStageExecutor stageExecutor = mock(RequirementStageExecutor.class);
        when(stageExecutor.execute(any(RequirementStageCommand.class))).thenAnswer(invocation -> {
            RequirementStageCommand command = invocation.getArgument(0);
            return new RequirementDeliveryResult(command.taskId(), RdTaskStatus.COMPLETED, "", "{}", "");
        });
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        taskStore.saveRequirementTask(requirementTask(
                "task-deferred", "project-saturated", RdTaskStatus.CREATED));
        AtomicLong idsClock = new AtomicLong(1_784_400_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, idsClock::getAndIncrement);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryRequirementDeliveryJobStore jobs = new InMemoryRequirementDeliveryJobStore();
        InMemoryRequirementStageCommandStore stages = new InMemoryRequirementStageCommandStore();
        long now = System.currentTimeMillis();
        for (int index = 0; index < 2; index++) {
            String commandId = "active-saturated-" + index;
            stages.enqueue(RequirementStageCommand.pending(
                    commandId, "active-task-" + index, 0L, 1L,
                    "REQUIREMENT_DELIVERY", "ROLE_EXECUTION:CODING_AGENT", 0, 3, 0L,
                    ScheduleResourceClass.DOCKER, "project-saturated", "", "P1", now));
            stages.claim(commandId, "other-worker", now, 3_600_000L).orElseThrow();
        }
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine,
                new TaskExecutorAdapter(new SyncTaskExecutor()),
                jobs,
                ids,
                registry,
                "test-worker",
                3,
                60_000L,
                null,
                null,
                stages,
                stageExecutor
        );

        var future = dispatcher.submit("task-deferred");

        assertFalse(future.isDone(), "quota-deferred submit must remain associated with its command");
        stages.complete("active-saturated-0", "other-worker", now + 1);
        dispatcher.recover();

        assertEquals(RdTaskStatus.COMPLETED, future.get(1, TimeUnit.SECONDS).status());
        verify(stageExecutor).execute(any(RequirementStageCommand.class));
    }

    @Test
    void shouldReleaseLocalFutureWhenAnotherDispatcherCompletesSharedCommand() throws Exception {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        RequirementStageExecutor firstStageExecutor = mock(RequirementStageExecutor.class);
        RequirementStageExecutor secondStageExecutor = mock(RequirementStageExecutor.class);
        when(secondStageExecutor.execute(any(RequirementStageCommand.class))).thenAnswer(invocation -> {
            RequirementStageCommand command = invocation.getArgument(0);
            return new RequirementDeliveryResult(command.taskId(), RdTaskStatus.COMPLETED, "", "{}", "");
        });
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        taskStore.saveRequirementTask(requirementTask(
                "task-cross-instance", "project-cross-instance", RdTaskStatus.CREATED));
        AtomicLong idsClock = new AtomicLong(1_784_450_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, idsClock::getAndIncrement);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryRequirementDeliveryJobStore jobs = new InMemoryRequirementDeliveryJobStore();
        InMemoryRequirementStageCommandStore stages = new InMemoryRequirementStageCommandStore();
        long now = System.currentTimeMillis();
        for (int index = 0; index < 2; index++) {
            String commandId = "cross-instance-active-" + index;
            stages.enqueue(RequirementStageCommand.pending(
                    commandId, "cross-instance-active-task-" + index, 0L, 1L,
                    "REQUIREMENT_DELIVERY", "ROLE_EXECUTION:CODING_AGENT", 0, 3, 0L,
                    ScheduleResourceClass.DOCKER, "project-cross-instance", "", "P1", now));
            stages.claim(commandId, "other-worker", now, 3_600_000L).orElseThrow();
        }

        RequirementDeliveryDispatchService firstDispatcher = new RequirementDeliveryDispatchService(
                engine,
                new TaskExecutorAdapter(new SyncTaskExecutor()),
                jobs,
                ids,
                registry,
                "worker-a",
                3,
                60_000L,
                null,
                null,
                stages,
                firstStageExecutor
        );
        var localFuture = firstDispatcher.submit("task-cross-instance");
        assertFalse(localFuture.isDone(), "the saturated first dispatcher must retain its local future");

        stages.complete("cross-instance-active-0", "other-worker", now + 1);

        RequirementDeliveryDispatchService secondDispatcher = new RequirementDeliveryDispatchService(
                engine,
                new TaskExecutorAdapter(new SyncTaskExecutor()),
                jobs,
                ids,
                registry,
                "worker-b",
                3,
                60_000L,
                null,
                null,
                stages,
                secondStageExecutor
        );
        secondDispatcher.recover();

        RequirementStageCommand completed = stages.find(
                "task-cross-instance", "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING").orElseThrow();
        assertEquals(RequirementStageCommand.Status.SUCCEEDED, completed.status());
        verify(secondStageExecutor).execute(any(RequirementStageCommand.class));
        verify(firstStageExecutor, never()).execute(any(RequirementStageCommand.class));

        firstDispatcher.recover();

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> localFuture.get(1, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("completed by another worker"));
        assertTrue(localFuture.isDone());
    }

    @Test
    void shouldPersistRetryableFailureInsteadOfLosingRejectedThreadPoolWork() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        when(engine.submit("task-2")).thenThrow(new IllegalStateException("boom"));
        InMemoryRequirementDeliveryJobStore store = new InMemoryRequirementDeliveryJobStore();
        RequirementDeliveryDispatchService dispatcher = dispatcher(engine, store);

        assertThrows(RuntimeException.class, () -> dispatcher.submit("task-2").join());

        assertEquals(RequirementDeliveryJobStatus.FAILED_RETRYABLE,
                store.findByTask("task-2").orElseThrow().status());
    }

    @Test
    void shouldPersistLinkageErrorAsRetryableFailureInsteadOfStrandingTheDeliveryJob() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        when(engine.submit("task-linkage")).thenThrow(new NoSuchMethodError("stale runtime artifact"));
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        taskStore.saveRequirementTask(requirementTask("task-linkage", RdTaskStatus.CREATED));
        AtomicLong now = new AtomicLong(1_784_200_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryRequirementDeliveryJobStore jobs = new InMemoryRequirementDeliveryJobStore();
        InMemoryRequirementStageCommandStore stages = new InMemoryRequirementStageCommandStore();
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine, new TaskExecutorAdapter(new SyncTaskExecutor()), jobs, ids, registry,
                "test-worker", 3, 60_000L, null, null, stages,
                command -> engine.submit(command.taskId()));

        assertThrows(RuntimeException.class, () -> dispatcher.submit("task-linkage").join());

        assertEquals(RequirementDeliveryJobStatus.FAILED_RETRYABLE,
                jobs.findByTask("task-linkage").orElseThrow().status());
        RdRequirementTask failed = (RdRequirementTask) registry.getTask("task-linkage");
        assertEquals(RdTaskStatus.FAILED_RETRYABLE, failed.status());
        org.junit.jupiter.api.Assertions.assertTrue(
                failed.executionResultJson().contains("\"errorCategory\":\"NoSuchMethodError\""));
    }

    @Test
    void shouldKeepPersistedJobPendingWhenTheLocalExecutorRejectsScheduling() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        InMemoryRequirementDeliveryJobStore store = new InMemoryRequirementDeliveryJobStore();
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        doThrow(new TaskRejectedException("queue full")).when(executor).execute(any(Runnable.class));
        AtomicLong now = new AtomicLong(1_784_100_000_000L);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine, executor, store, new SnowflakeIdGenerator(1, 1, now::getAndIncrement),
                authoritativeRegistry(), "test-worker", 3, 60_000L);

        assertDoesNotThrow(() -> dispatcher.submit("task-pending"));

        assertEquals(RequirementDeliveryJobStatus.PENDING,
                store.findByTask("task-pending").orElseThrow().status());
    }

    @Test
    void shouldFailJobWhenEngineReturnsRetryableOrNeedsHumanStatusWithoutThrowing() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        when(engine.submit("task-3")).thenReturn(new RequirementDeliveryResult(
                "task-3", RdTaskStatus.FAILED_RETRYABLE, "", "{}", "stage boom"));
        InMemoryRequirementDeliveryJobStore store = new InMemoryRequirementDeliveryJobStore();
        RequirementDeliveryDispatchService dispatcher = dispatcher(engine, store);

        dispatcher.submit("task-3").join();

        assertEquals(RequirementDeliveryJobStatus.FAILED_RETRYABLE,
                store.findByTask("task-3").orElseThrow().status());
        assertTrue(dispatcher.metricsSnapshot().retries() >= 1L);
    }

    @Test
    void shouldDeadLetterExhaustedExpiredLeaseDuringRecovery() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        InMemoryRequirementDeliveryJobStore store = new InMemoryRequirementDeliveryJobStore();
        store.enqueue(RequirementDeliveryJob.pending("job-1", "task-4", 1, 100L));
        store.claim("task-4", "old-worker", 110L, 10L).orElseThrow();
        RequirementDeliveryDispatchService dispatcher = dispatcher(engine, store);

        dispatcher.recover();

        assertEquals(RequirementDeliveryJobStatus.DEAD_LETTERED,
                store.findByTask("task-4").orElseThrow().status());
    }

    @Test
    void recoverSkipsProjectWhenInFlightAlreadyAtCap() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        when(engine.submit(any())).thenAnswer(invocation -> {
            String taskId = invocation.getArgument(0);
            return new RequirementDeliveryResult(taskId, RdTaskStatus.COMPLETED, "", "{}", "");
        });
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        // FairScheduleLimits.defaults().maxPerProject() == 2
        taskStore.saveRequirementTask(requirementTask("t-a0", "proj-a", RdTaskStatus.EXECUTING));
        taskStore.saveRequirementTask(requirementTask("t-a1", "proj-a", RdTaskStatus.EXECUTING));
        taskStore.saveRequirementTask(requirementTask("t-a2", "proj-a", RdTaskStatus.CREATED));
        taskStore.saveRequirementTask(requirementTask("t-b1", "proj-b", RdTaskStatus.CREATED));
        AtomicLong idsClock = new AtomicLong(1_784_300_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, idsClock::getAndIncrement);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryRequirementDeliveryJobStore jobs = new InMemoryRequirementDeliveryJobStore();
        InMemoryRequirementStageCommandStore stages = new InMemoryRequirementStageCommandStore();
        long leaseNow = System.currentTimeMillis();
        jobs.enqueue(RequirementDeliveryJob.pending("j-a0", "t-a0", 3, leaseNow));
        jobs.claim("t-a0", "worker-live", leaseNow, 3_600_000L).orElseThrow();
        jobs.enqueue(RequirementDeliveryJob.pending("j-a1", "t-a1", 3, leaseNow + 1));
        jobs.claim("t-a1", "worker-live", leaseNow + 1, 3_600_000L).orElseThrow();
        jobs.enqueue(RequirementDeliveryJob.pending("j-a2", "t-a2", 3, leaseNow + 2));
        jobs.enqueue(RequirementDeliveryJob.pending("j-b1", "t-b1", 3, leaseNow + 3));
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine,
                new TaskExecutorAdapter(new SyncTaskExecutor()),
                jobs,
                ids,
                registry,
                "test-worker",
                3,
                600_000L,
                null,
                null,
                stages,
                command -> engine.submit(command.taskId())
        );

        dispatcher.recover();

        org.mockito.Mockito.verify(engine, org.mockito.Mockito.times(1)).submit("t-b1");
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.never()).submit("t-a2");
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.never()).submit("t-a0");
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.never()).submit("t-a1");
        assertEquals(RequirementDeliveryJobStatus.RUNNING, jobs.findByTask("t-a0").orElseThrow().status());
        assertEquals(RequirementDeliveryJobStatus.RUNNING, jobs.findByTask("t-a1").orElseThrow().status());
        assertEquals(RequirementDeliveryJobStatus.PENDING, jobs.findByTask("t-a2").orElseThrow().status());
        assertEquals(RequirementDeliveryJobStatus.SUCCEEDED, jobs.findByTask("t-b1").orElseThrow().status());
        assertEquals(2, jobs.listInFlight(System.currentTimeMillis()).size());
        assertTrue(dispatcher.metricsSnapshot().inFlightSamples() >= 0L);
        assertTrue(dispatcher.metricsSnapshot().projectClaims().isEmpty());
    }

    @Test
    void shouldCompleteActiveRetryCheckpointWithDeliveryOutcome() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        when(engine.submit("task-retry")).thenReturn(new RequirementDeliveryResult(
                "task-retry", RdTaskStatus.COMPLETED, "", "{}", ""));
        InMemoryRequirementDeliveryJobStore jobs = new InMemoryRequirementDeliveryJobStore();
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        TaskRetryPoint point = new TaskRetryPoint("task-retry", TaskFailurePhase.PR_PUBLICATION,
                null, "", "", "", "publish failed", 9L);
        TaskRetryCheckpoint created = checkpoints.createOrGet(TaskRetryCheckpoint.created(
                "checkpoint-1", point, 1, "task-retry:9:PR_PUBLICATION:",
                RdTaskStatus.REJECTED, 100L)).checkpoint();
        checkpoints.transition(created.checkpointId(), TaskRetryCheckpointStatus.CREATED,
                TaskRetryCheckpointStatus.DISPATCHED, "", 110L);
        RequirementDeliveryDispatchService dispatcher = dispatcher(engine, jobs, checkpoints);

        dispatcher.submit("task-retry").join();

        assertEquals(TaskRetryCheckpointStatus.SUCCEEDED,
                checkpoints.find("checkpoint-1").orElseThrow().status());
    }

    @Test
    void shouldKeepTheTaskSnapshotRunnableWhenAnEarlyPipelineFailureCanRetry() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        when(engine.submit("task-context")).thenThrow(new IllegalStateException("context provider timeout"));
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        taskStore.saveRequirementTask(requirementTask("task-context", RdTaskStatus.CONTEXT_BUILDING));
        AtomicLong now = new AtomicLong(1_784_200_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryRequirementDeliveryJobStore jobs = new InMemoryRequirementDeliveryJobStore();
        InMemoryRequirementStageCommandStore stages = new InMemoryRequirementStageCommandStore();
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine, new TaskExecutorAdapter(new SyncTaskExecutor()), jobs, ids, registry,
                "test-worker", 3, 60_000L, null, null, stages,
                command -> engine.submit(command.taskId()));
        RdRequirementTask before = taskStore.findRequirementTask("task-context").orElseThrow();

        assertThrows(RuntimeException.class, () -> dispatcher.submit("task-context").join());

        RdRequirementTask unchanged = (RdRequirementTask) registry.getTask("task-context");
        assertEquals(RdTaskStatus.CONTEXT_BUILDING, unchanged.status());
        assertEquals(before, unchanged);
    }

    @Test
    void shouldPersistTheRetryableCheckpointOnlyWhenTheEarlyPipelineCommandIsExhausted() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        when(engine.submit("task-context-exhausted")).thenThrow(new IllegalStateException("context provider timeout"));
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        taskStore.saveRequirementTask(requirementTask("task-context-exhausted", RdTaskStatus.CONTEXT_BUILDING));
        AtomicLong now = new AtomicLong(1_784_200_100_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine, new TaskExecutorAdapter(new SyncTaskExecutor()), new InMemoryRequirementDeliveryJobStore(), ids,
                registry, "test-worker", 1, 60_000L, null, null,
                new InMemoryRequirementStageCommandStore(), command -> engine.submit(command.taskId()));

        assertThrows(RuntimeException.class, () -> dispatcher.submit("task-context-exhausted").join());

        assertEquals(RdTaskStatus.FAILED_RETRYABLE,
                registry.getTask("task-context-exhausted").status());
    }

    @Test
    void completionCasFailureMustNotEnqueueContinuation() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        InMemoryRequirementStageCommandStore durableCommands = new InMemoryRequirementStageCommandStore();
        RequirementStageCommandStore commands = new CompletionFailingStageCommandStore(durableCommands);
        AtomicLong now = new AtomicLong(1_784_600_000_000L);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine,
                new TaskExecutorAdapter(new SyncTaskExecutor()),
                new InMemoryRequirementDeliveryJobStore(),
                new SnowflakeIdGenerator(1, 1, now::getAndIncrement),
                authoritativeRegistry(),
                "test-worker",
                3,
                60_000L,
                null,
                null,
                commands,
                command -> new RequirementDeliveryResult(
                        command.taskId(), RdTaskStatus.MATERIAL_COLLECTING, "", "{}", "")
        );

        dispatcher.submit("task-finalization-cas");

        assertTrue(durableCommands.find(
                "task-finalization-cas", "REQUIREMENT_DELIVERY", "MATERIAL_READY").isEmpty(),
                "a continuation must not be persisted after current-command completion loses its lease CAS");
    }

    @Test
    void recoveryFinalizesOutcomeRecordedPlanWithoutReplanningOrExecutingTheStage() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        taskStore.saveRequirementTask(requirementTask("task-finalization-crash", RdTaskStatus.CREATED));
        AtomicLong idsClock = new AtomicLong(1_784_700_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, idsClock::getAndIncrement);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        long now = System.currentTimeMillis();
        RequirementStageCommand command = RequirementStageCommand.pending(
                "stage-outcome-recorded", "task-finalization-crash", 10L, 20L,
                "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING", 0, 3, now + 60_000L,
                ScheduleResourceClass.GENERIC, "project-1", "", "P1", now);
        commands.enqueue(command);
        RequirementStageCommand claimed = commands.claim(
                command.commandId(), "test-worker", now, 60_000L).orElseThrow();
        RequirementStageFinalization marker = RequirementStageFinalization.prepared(
                claimed, RdTaskStatus.CREATED, now).outcomeRecorded(
                RdTaskStatus.MATERIAL_COLLECTING, "{\"durable\":true}", "digest", now + 1L);
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                claimed.taskId(), claimed.taskVersion(), claimed.fencingToken(), RdTaskStatus.CREATED,
                List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING, "", "{}", "", "", "")),
                com.wish.rd.engine.requirement.job.model.CommandDisposition.SUCCEEDED,
                new com.wish.rd.engine.requirement.job.model.ContinuationSpec(
                        "REQUIREMENT_DELIVERY", "MATERIAL_READY"),
                com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt.none());
        RequirementStageExecutor stageExecutor = mock(RequirementStageExecutor.class);
        RequirementStageFinalizationPort finalizer = mock(RequirementStageFinalizationPort.class);
        when(finalizer.findLatestPrepared(command.commandId())).thenReturn(Optional.of(marker));
        when(finalizer.decodeOutcomePlan(marker)).thenReturn(plan);
        RequirementDeliveryResult expected = new RequirementDeliveryResult(
                claimed.taskId(), RdTaskStatus.MATERIAL_COLLECTING, "", "{}", "");
        when(finalizer.finalize(any())).thenReturn(new RequirementStageFinalizationPort.FinalizationResult(
                marker.finalized(expected, "", now + 2L), claimed.succeeded(now + 2L), null));
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine,
                new TaskExecutorAdapter(new SyncTaskExecutor()),
                new InMemoryRequirementDeliveryJobStore(),
                ids,
                registry,
                "test-worker",
                3,
                1L,
                null,
                null,
                commands,
                stageExecutor,
                finalizer,
                RequirementDeliverySchedulingPolicy.defaults(),
                null
        );

        CompletableFuture<RequirementDeliveryResult> future = new CompletableFuture<>();
        invokeRunClaimedCommand(dispatcher, claimed, future);

        assertEquals(expected, future.join());
        verify(finalizer).decodeOutcomePlan(marker);
        org.mockito.ArgumentCaptor<RequirementStageFinalizationPort.FinalizationCommand> finalization =
                org.mockito.ArgumentCaptor.forClass(RequirementStageFinalizationPort.FinalizationCommand.class);
        verify(finalizer).finalize(finalization.capture());
        assertEquals("MATERIAL_READY", finalization.getValue().nextCommand().stage());
        assertEquals(plan.postVersion(), finalization.getValue().nextCommand().taskVersion());
        assertEquals(plan.postFencingToken(), finalization.getValue().nextCommand().fencingToken());
        verify(stageExecutor, never()).plan(any(RequirementStageCommand.class));
        verify(stageExecutor, never()).execute(any(RequirementStageCommand.class));
    }

    @Test
    void checkpointBoundCodingSuccessContinuesAsThePreBoundQaRetryCommand() {
        long now = System.currentTimeMillis();
        String taskId = "task-checkpoint-continue";
        String checkpointId = "101";
        String codingBindingId = "201";
        String qaBindingId = "202";
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        InMemoryTaskRetryAttemptBindingStore bindings = new InMemoryTaskRetryAttemptBindingStore();
        TaskRetryCheckpoint checkpoint = new TaskRetryCheckpoint(
                checkpointId, taskId, TaskFailurePhase.AGENT_ROLE, AgentRole.CODING_AGENT,
                "coding-1", "", "", 1, taskId + ":11:AGENT_ROLE:CODING_AGENT",
                RdTaskStatus.FAILED_NEEDS_HUMAN, 11L, 12L,
                "command-old", "ROLE_EXECUTION:CODING_AGENT", "policy-1", "",
                "sha256:" + "a".repeat(64), "", "", 0L, 0L, "", Long.parseLong(checkpointId),
                "", List.of(), TaskRetryCheckpointStatus.CREATED, "qa failed", "", now, now);
        checkpoints.createOrGet(checkpoint);
        checkpoints.dispatch(checkpointId, 18L, 19L, "coding-command", now);
        bindings.save(new TaskRetryAttemptBinding(
                codingBindingId, checkpointId, TaskRetryAttemptKind.AGENT_STAGE,
                AgentRole.CODING_AGENT, "coding-2", "", 2, 0));
        bindings.save(new TaskRetryAttemptBinding(
                qaBindingId, checkpointId, TaskRetryAttemptKind.AGENT_STAGE,
                AgentRole.QA_AGENT, "qa-4", "", 4, 0));
        RequirementStageCommand pending = RequirementStageCommand.pending(
                "coding-command", taskId, 18L, 19L,
                AgentRole.CODING_AGENT.name(), "ROLE_EXECUTION:CODING_AGENT",
                0, 3, now + 3_600_000L, ScheduleResourceClass.DOCKER,
                Set.of(ScheduleResourceClass.DOCKER), "project-1", "provider", "P1",
                "policy-1", checkpointId, Long.parseLong(checkpointId), codingBindingId, now);
        commands.enqueue(pending);
        RequirementStageCommand claimed = commands.claim(
                pending.commandId(), "test-worker", now, 60_000L).orElseThrow();
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                claimed.taskId(), claimed.taskVersion(), claimed.fencingToken(), RdTaskStatus.EXECUTING,
                List.of(RequirementTaskMutation.snapshotUpdate(
                        RdTaskStatus.EXECUTING, "", "{\"ok\":true}", "", "", "")),
                com.wish.rd.engine.requirement.job.model.CommandDisposition.SUCCEEDED,
                new com.wish.rd.engine.requirement.job.model.ContinuationSpec(
                        AgentRole.QA_AGENT.name(), "ROLE_EXECUTION:" + AgentRole.QA_AGENT.name()),
                com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt.none());
        RequirementStageFinalization marker = RequirementStageFinalization.prepared(
                claimed, RdTaskStatus.EXECUTING, now);
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        when(registry.getTask(taskId)).thenReturn(
                requirementTask(taskId, RdTaskStatus.EXECUTING).withConcurrency(18L, 19L));
        RequirementStageExecutor stageExecutor = mock(RequirementStageExecutor.class);
        when(stageExecutor.plan(claimed)).thenReturn(plan);
        RequirementStageFinalizationPort finalizer = mock(RequirementStageFinalizationPort.class);
        when(finalizer.findLatestPrepared(claimed.commandId())).thenReturn(Optional.empty());
        when(finalizer.prepare(any(), any(), any(), anyLong())).thenReturn(marker);
        when(finalizer.recordOutcome(any(), any(), any(), any(), anyLong())).thenReturn(marker);
        ArgumentCaptor<RequirementStageFinalizationPort.FinalizationCommand> finalization =
                ArgumentCaptor.forClass(RequirementStageFinalizationPort.FinalizationCommand.class);
        when(finalizer.finalize(finalization.capture())).thenAnswer(invocation -> {
            RequirementStageFinalizationPort.FinalizationCommand command = invocation.getArgument(0);
            RequirementDeliveryResult outcome = new RequirementDeliveryResult(
                    taskId, RdTaskStatus.EXECUTING, "", "{\"ok\":true}", "");
            return new RequirementStageFinalizationPort.FinalizationResult(
                    marker.finalized(outcome, command.nextCommand() == null ? "" : command.nextCommand().commandId(),
                            now + 1L),
                    claimed.succeeded(now + 1L),
                    command.nextCommand());
        });
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class),
                new TaskExecutorAdapter(runnable -> {
                }),
                new InMemoryRequirementDeliveryJobStore(),
                SnowflakeIdGenerator.defaultGenerator(),
                registry,
                "test-worker",
                3,
                60_000L,
                null,
                checkpoints,
                commands,
                stageExecutor,
                finalizer,
                RequirementDeliverySchedulingPolicy.defaults(),
                null,
                bindings);

        invokeRunClaimedCommand(dispatcher, claimed, new CompletableFuture<>());

        RequirementStageCommand next = finalization.getValue().nextCommand();
        assertEquals(AgentRole.QA_AGENT.name(), next.role());
        assertEquals("ROLE_EXECUTION:" + AgentRole.QA_AGENT.name(), next.stage());
        assertEquals(checkpointId, next.retryCheckpointId());
        assertEquals(Long.parseLong(checkpointId), next.businessGeneration());
        assertEquals(qaBindingId, next.targetRetryBindingId());
        assertEquals("policy-1", next.policyRunId());
        assertEquals(TaskRetryCheckpointStatus.DISPATCHED,
                checkpoints.find(checkpointId).orElseThrow().status());
    }

    @Test
    void recoveryReplaysTheOriginalRetryableFailurePlanAtTheFinalAttempt() {
        long now = System.currentTimeMillis();
        RequirementStageCommand initial = RequirementStageCommand.pending(
                "recorded-retryable", "task-recorded-retryable", 10L, 20L,
                "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING", 0, 2, now + 60_000L,
                ScheduleResourceClass.GENERIC, "project-1", "", "P1", now);
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        commands.enqueue(initial);
        RequirementStageCommand firstAttempt = commands.claim(
                initial.commandId(), "test-worker", now, 60_000L).orElseThrow();
        RequirementStageCommand finalAttempt = commands.fail(
                firstAttempt, "test-worker", "provider unavailable", now + 1L);
        finalAttempt = commands.claim(finalAttempt.commandId(), "test-worker", now + 2L, 60_000L).orElseThrow();
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                finalAttempt.taskId(), finalAttempt.taskVersion(), finalAttempt.fencingToken(), RdTaskStatus.CREATED,
                List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.CREATED, RdTaskStatus.FAILED_RETRYABLE, "", "{\"status\":\"FAILED\"}",
                        "", "provider unavailable", "provider unavailable")),
                com.wish.rd.engine.requirement.job.model.CommandDisposition.RETRYABLE_TECHNICAL_FAILURE,
                com.wish.rd.engine.requirement.job.model.ContinuationSpec.terminal(),
                com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt.none());
        RequirementStageFinalization marker = RequirementStageFinalization.prepared(
                firstAttempt, RdTaskStatus.CREATED, now).outcomeRecorded(
                plan.postStatus(), "{\"durable\":true}", "digest", now + 1L);
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        when(registry.getTask(finalAttempt.taskId())).thenReturn(requirementTask(finalAttempt.taskId(), RdTaskStatus.CREATED));
        RequirementStageFinalizationPort finalizer = mock(RequirementStageFinalizationPort.class);
        when(finalizer.findLatestPrepared(finalAttempt.commandId())).thenReturn(Optional.of(marker));
        when(finalizer.decodeOutcomePlan(marker)).thenReturn(plan);
        RequirementDeliveryResult expected = new RequirementDeliveryResult(
                finalAttempt.taskId(), RdTaskStatus.FAILED_RETRYABLE, "", "{\"status\":\"FAILED\"}",
                "provider unavailable");
        when(finalizer.finalize(any())).thenReturn(new RequirementStageFinalizationPort.FinalizationResult(
                marker.finalized(expected, "", now + 3L),
                finalAttempt.failed("provider unavailable", now + 3L), null));
        RequirementStageExecutor stageExecutor = mock(RequirementStageExecutor.class);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()),
                new InMemoryRequirementDeliveryJobStore(), SnowflakeIdGenerator.defaultGenerator(), registry,
                "test-worker", 3, 60_000L, null, null, commands, stageExecutor, finalizer,
                RequirementDeliverySchedulingPolicy.defaults(), null);

        CompletableFuture<RequirementDeliveryResult> future = new CompletableFuture<>();
        invokeRunClaimedCommand(dispatcher, finalAttempt, future);

        assertEquals(expected, future.join());
        org.mockito.ArgumentCaptor<RequirementStageFinalizationPort.FinalizationCommand> finalized =
                org.mockito.ArgumentCaptor.forClass(RequirementStageFinalizationPort.FinalizationCommand.class);
        verify(finalizer).finalize(finalized.capture());
        assertEquals(plan, finalized.getValue().plan());
        assertEquals(RequirementStageFinalizationPort.TaskMutationDisposition.APPLY,
                finalized.getValue().taskMutationDisposition());
        verify(stageExecutor, never()).plan(any(RequirementStageCommand.class));
        verify(stageExecutor, never()).execute(any(RequirementStageCommand.class));
    }

    @Test
    void recoveryUsesAlreadyAppliedDispositionWhenTheAuthoritativeSnapshotMatchesRecordedPlanPostState() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        long now = System.currentTimeMillis();
        RequirementStageCommand command = RequirementStageCommand.pending(
                "stage-post-snapshot", "task-post-snapshot", 10L, 20L,
                "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING", 0, 3, now + 60_000L,
                ScheduleResourceClass.GENERIC, "project-1", "", "P1", now);
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        commands.enqueue(command);
        RequirementStageCommand claimed = commands.claim(command.commandId(), "test-worker", now, 60_000L).orElseThrow();
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                claimed.taskId(), claimed.taskVersion(), claimed.fencingToken(), RdTaskStatus.CREATED,
                List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING, "", "{}", "", "", "")),
                com.wish.rd.engine.requirement.job.model.CommandDisposition.SUCCEEDED,
                com.wish.rd.engine.requirement.job.model.ContinuationSpec.terminal(),
                com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt.none());
        RequirementStageFinalization marker = RequirementStageFinalization.prepared(
                claimed, RdTaskStatus.CREATED, now).outcomeRecorded(plan.postStatus(), "{\"durable\":true}", "digest", now);
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        when(registry.getTask(claimed.taskId())).thenReturn(
                requirementTask(claimed.taskId(), RdTaskStatus.MATERIAL_COLLECTING).withConcurrency(11L, 21L));
        RequirementStageFinalizationPort finalizer = mock(RequirementStageFinalizationPort.class);
        when(finalizer.findLatestPrepared(claimed.commandId())).thenReturn(Optional.of(marker));
        when(finalizer.decodeOutcomePlan(marker)).thenReturn(plan);
        RequirementDeliveryResult expected = new RequirementDeliveryResult(
                claimed.taskId(), RdTaskStatus.MATERIAL_COLLECTING, "", "{}", "");
        when(finalizer.finalize(any())).thenReturn(new RequirementStageFinalizationPort.FinalizationResult(
                marker.finalized(expected, "", now + 1L), claimed.succeeded(now + 1L), null));
        RequirementStageExecutor stageExecutor = mock(RequirementStageExecutor.class);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine, new TaskExecutorAdapter(new SyncTaskExecutor()), new InMemoryRequirementDeliveryJobStore(),
                SnowflakeIdGenerator.defaultGenerator(), registry, "test-worker", 3, 60_000L,
                null, null, commands, stageExecutor, finalizer, RequirementDeliverySchedulingPolicy.defaults(), null);

        CompletableFuture<RequirementDeliveryResult> future = new CompletableFuture<>();
        invokeRunClaimedCommand(dispatcher, claimed, future);

        assertEquals(expected, future.join());
        org.mockito.ArgumentCaptor<RequirementStageFinalizationPort.FinalizationCommand> finalization =
                org.mockito.ArgumentCaptor.forClass(RequirementStageFinalizationPort.FinalizationCommand.class);
        verify(finalizer).finalize(finalization.capture());
        assertEquals(RequirementStageFinalizationPort.TaskMutationDisposition.ALREADY_APPLIED,
                finalization.getValue().taskMutationDisposition());
        verify(stageExecutor, never()).plan(any(RequirementStageCommand.class));
    }

    @Test
    void finalizationFailurePreservesRecordedOutcomeForLeaseRecovery() {
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        taskStore.saveRequirementTask(requirementTask("task-finalization-retry", RdTaskStatus.CREATED));
        AtomicLong idsClock = new AtomicLong(1_784_710_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, idsClock::getAndIncrement);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        long now = System.currentTimeMillis();
        RequirementStageCommand pending = RequirementStageCommand.pending(
                "stage-finalization-retry", "task-finalization-retry", 10L, 20L,
                "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING", 0, 3, now + 60_000L,
                ScheduleResourceClass.GENERIC, "project-1", "", "P1", now);
        commands.enqueue(pending);
        RequirementStageCommand claimed = commands.claim(
                pending.commandId(), "test-worker", now, 60_000L).orElseThrow();
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                claimed.taskId(), claimed.taskVersion(), claimed.fencingToken(), RdTaskStatus.CREATED,
                List.of(RequirementTaskMutation.statusTransition(
                        RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING, "", "{}", "", "", "")),
                com.wish.rd.engine.requirement.job.model.CommandDisposition.SUCCEEDED,
                com.wish.rd.engine.requirement.job.model.ContinuationSpec.terminal(),
                com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt.none());
        RequirementStageFinalization marker = RequirementStageFinalization.prepared(
                claimed, RdTaskStatus.CREATED, now).outcomeRecorded(
                plan.postStatus(), "{\"durable\":true}", "digest", now + 1L);
        RequirementStageExecutor stageExecutor = mock(RequirementStageExecutor.class);
        RequirementStageFinalizationPort finalizer = mock(RequirementStageFinalizationPort.class);
        when(finalizer.findLatestPrepared(pending.commandId())).thenReturn(Optional.of(marker));
        when(finalizer.decodeOutcomePlan(marker)).thenReturn(plan);
        when(finalizer.finalize(any())).thenThrow(new IllegalStateException("injected transaction rollback"));
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class),
                new TaskExecutorAdapter(new SyncTaskExecutor()),
                new InMemoryRequirementDeliveryJobStore(), ids, registry,
                "test-worker", 3, 60_000L, null, null, commands,
                stageExecutor, finalizer, RequirementDeliverySchedulingPolicy.defaults(), null);

        CompletableFuture<RequirementDeliveryResult> future = new CompletableFuture<>();
        invokeRunClaimedCommand(dispatcher, claimed, future);

        assertThrows(java.util.concurrent.CompletionException.class, future::join);
        assertEquals(RdTaskStatus.CREATED, registry.getTask(claimed.taskId()).status());
        assertEquals(RequirementStageCommand.Status.RUNNING,
                commands.findById(claimed.commandId()).orElseThrow().status());
        verify(stageExecutor, never()).plan(any());
        verify(stageExecutor, never()).execute(any());
    }

    @Test
    void persistsProfileProviderDeadlineAndCombinedResourcesForEachRoleCommand() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        taskStore.saveRequirementTask(requirementTask(
                "task-resources", "project-resources", RdTaskStatus.EXECUTING));
        AtomicLong idsClock = new AtomicLong(1_784_500_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, idsClock::getAndIncrement);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        AgentExecutionProfileService profiles = profilesFor("project-resources");
        InMemoryRequirementStageCommandStore stages = new InMemoryRequirementStageCommandStore();
        long firstRoleCreatedAt = System.currentTimeMillis();
        stages.enqueue(RequirementStageCommand.pending(
                "authorized-first-role", "task-resources", 10L, 20L,
                AgentRole.requirementDeliveryOrder().getFirst().name(),
                "ROLE_EXECUTION:" + AgentRole.requirementDeliveryOrder().getFirst().name(),
                0, 3, firstRoleCreatedAt + 120_000L, ScheduleResourceClass.PROVIDER,
                Set.of(ScheduleResourceClass.PROVIDER), "project-resources",
                "provider-requirement_reviewer", "P1", "policy-task-resources", firstRoleCreatedAt));
        List<RequirementStageCommand> observed = new ArrayList<>();
        List<AgentRole> roleOrder = AgentRole.requirementDeliveryOrder();
        RequirementStageExecutor stageExecutor = new RequirementStageExecutor() {
            @Override
            public RequirementStageExecutionPlan plan(RequirementStageCommand command) {
                observed.add(command);
                com.wish.rd.engine.requirement.job.model.ContinuationSpec continuation =
                        com.wish.rd.engine.requirement.job.model.ContinuationSpec.terminal();
                if (command.stage().startsWith("ROLE_EXECUTION:")) {
                    int index = roleOrder.indexOf(AgentRole.valueOf(command.role()));
                    if (index >= 0 && index + 1 < roleOrder.size()) {
                        AgentRole next = roleOrder.get(index + 1);
                        continuation = new com.wish.rd.engine.requirement.job.model.ContinuationSpec(
                                next.name(), "ROLE_EXECUTION:" + next.name());
                    } else {
                        continuation = new com.wish.rd.engine.requirement.job.model.ContinuationSpec(
                                "REQUIREMENT_DELIVERY", "DETERMINISTIC_REVIEW");
                    }
                }
                return new RequirementStageExecutionPlan(
                        RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                        command.taskId(),
                        command.taskVersion(),
                        command.fencingToken(),
                        RdTaskStatus.EXECUTING,
                        List.of(),
                        com.wish.rd.engine.requirement.job.model.CommandDisposition.SUCCEEDED,
                        continuation,
                        com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt.none());
            }

            @Override
            public RequirementDeliveryResult execute(RequirementStageCommand command) {
                throw new AssertionError("role resource test must use plan()");
            }
        };
        RequirementDeliverySchedulingPolicy policy = new RequirementDeliverySchedulingPolicy(
                new FairScheduleLimits(4, 2, 1, 4, 2, 8, 60_000L),
                Map.of("project-resources", 2),
                120_000L,
                "configured-provider"
        );
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine,
                new TaskExecutorAdapter(new SyncTaskExecutor()),
                new InMemoryRequirementDeliveryJobStore(),
                ids,
                registry,
                "test-worker",
                3,
                60_000L,
                null,
                null,
                stages,
                stageExecutor,
                policy,
                profiles
        );

        dispatcher.submit("task-resources").join();

        Map<String, RequirementStageCommand> commandsByRole = observed.stream()
                .filter(command -> command.stage().startsWith("ROLE_EXECUTION:"))
                .collect(java.util.stream.Collectors.toMap(
                        RequirementStageCommand::role,
                        command -> command
                ));
        RequirementStageCommand architect = commandsByRole.get(AgentRole.SOLUTION_ARCHITECT.name());
        RequirementStageCommand reviewer = commandsByRole.get(AgentRole.REQUIREMENT_REVIEWER.name());
        RequirementStageCommand coding = commandsByRole.get(AgentRole.CODING_AGENT.name());
        RequirementStageCommand qa = commandsByRole.get(AgentRole.QA_AGENT.name());

        assertEquals("provider-solution_architect", architect.providerId());
        assertEquals("provider-requirement_reviewer", reviewer.providerId());
        assertEquals("provider-coding_agent", coding.providerId());
        assertEquals("provider-qa_agent", qa.providerId());
        assertEquals(Set.of(ScheduleResourceClass.PROVIDER), architect.resourceRequirements());
        assertEquals(Set.of(ScheduleResourceClass.PROVIDER), reviewer.resourceRequirements());
        assertEquals(Set.of(ScheduleResourceClass.PROVIDER, ScheduleResourceClass.DOCKER),
                coding.resourceRequirements());
        assertEquals(Set.of(
                ScheduleResourceClass.PROVIDER,
                ScheduleResourceClass.DOCKER,
                ScheduleResourceClass.BROWSER_QA), qa.resourceRequirements());
        for (RequirementStageCommand command : commandsByRole.values()) {
            assertEquals("policy-task-resources", command.policyRunId());
            assertEquals(command.createdAtEpochMillis() + policy.commandDeadlineMillis(),
                    command.deadlineEpochMillis());
        }
    }

    private RequirementDeliveryDispatchService dispatcher(
            RequirementDeliveryEngine engine,
            InMemoryRequirementDeliveryJobStore store
    ) {
        AtomicLong now = new AtomicLong(1_784_100_000_000L);
        InMemoryRequirementStageCommandStore stages = new InMemoryRequirementStageCommandStore();
        return new RequirementDeliveryDispatchService(
                engine,
                new TaskExecutorAdapter(new SyncTaskExecutor()),
                store,
                new SnowflakeIdGenerator(1, 1, now::getAndIncrement),
                authoritativeRegistry(),
                "test-worker",
                3,
                60_000L,
                null,
                null,
                stages,
                command -> engine.submit(command.taskId())
        );
    }

    private static RequirementDeliveryDispatchService continuationDispatcher(
            RagStreamTaskRegistry registry,
            RequirementStageCommandStore commands
    ) {
        return new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()),
                new InMemoryRequirementDeliveryJobStore(), SnowflakeIdGenerator.defaultGenerator(), registry,
                "test-worker", 3, 60_000L, null, null, commands, command -> {
                    throw new AssertionError("continuation command must not execute");
                });
    }

    private static RequirementStageCommand continuationPrevious() {
        return RequirementStageCommand.pending(
                "previous", "continuation-positive", 2L, 5L, "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING",
                0, 3, 60_000L, ScheduleResourceClass.GENERIC, "project", "", "P1", 1L);
    }

    private static RequirementStageCommand invokeNewCommand(
            RequirementDeliveryDispatchService dispatcher,
            String taskId,
            RequirementStageCommand previous,
            String role,
            String stage
    ) {
        try {
            Method method = RequirementDeliveryDispatchService.class.getDeclaredMethod(
                    "newCommand", String.class, RequirementStageCommand.class, String.class, String.class, long.class);
            method.setAccessible(true);
            return (RequirementStageCommand) method.invoke(dispatcher, taskId, previous, role, stage, 1L);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static RequirementDeliveryResult invokeAlreadyAppliedMutation(
            RequirementDeliveryDispatchService dispatcher,
            com.wish.rd.engine.requirement.job.model.RequirementStageFinalization marker
    ) {
        try {
            Method method = RequirementDeliveryDispatchService.class.getDeclaredMethod(
                    "alreadyAppliedMutation",
                    com.wish.rd.engine.requirement.job.model.RequirementStageFinalization.class);
            method.setAccessible(true);
            return (RequirementDeliveryResult) method.invoke(dispatcher, marker);
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new AssertionError(exception);
        } catch (InvocationTargetException exception) {
            throw new AssertionError(exception.getCause());
        }
    }

    private RequirementDeliveryDispatchService dispatcher(
            RequirementDeliveryEngine engine,
            InMemoryRequirementDeliveryJobStore jobs,
            InMemoryRequirementStageCommandStore stages,
            RequirementStageExecutor stageExecutor
    ) {
        AtomicLong now = new AtomicLong(1_784_100_000_000L);
        return new RequirementDeliveryDispatchService(
                engine, new TaskExecutorAdapter(new SyncTaskExecutor()), jobs,
                new SnowflakeIdGenerator(1, 1, now::getAndIncrement), authoritativeRegistry(),
                "test-worker", 3, 60_000L, null, null, stages, stageExecutor
        );
    }

    private RequirementDeliveryDispatchService dispatcher(
            RequirementDeliveryEngine engine,
            InMemoryRequirementDeliveryJobStore jobs,
            InMemoryRequirementStageCommandStore stages
    ) {
        AtomicLong now = new AtomicLong(1_784_100_000_000L);
        return new RequirementDeliveryDispatchService(
                engine, new TaskExecutorAdapter(new SyncTaskExecutor()), jobs,
                new SnowflakeIdGenerator(1, 1, now::getAndIncrement), authoritativeRegistry(),
                "test-worker", 3, 60_000L, null, null, stages,
                command -> engine.submit(command.taskId())
        );
    }

    private RequirementDeliveryDispatchService dispatcher(
            RequirementDeliveryEngine engine,
            InMemoryRequirementDeliveryJobStore store,
            InMemoryTaskRetryCheckpointStore checkpoints
    ) {
        AtomicLong now = new AtomicLong(1_784_100_000_000L);
        InMemoryRequirementStageCommandStore stages = new InMemoryRequirementStageCommandStore();
        return new RequirementDeliveryDispatchService(
                engine, new TaskExecutorAdapter(new SyncTaskExecutor()), store,
                new SnowflakeIdGenerator(1, 1, now::getAndIncrement), authoritativeRegistry(),
                "test-worker", 3, 60_000L, null, checkpoints, stages,
                command -> engine.submit(command.taskId()));
    }

    private static RdRequirementTask requirementTask(String taskId, RdTaskStatus status) {
        return requirementTask(taskId, "project-1", status);
    }

    private static RequirementStageCommand approvalResumeCommand(
            String commandId, String taskId, long version, long fence
    ) {
        return RequirementStageCommand.pending(
                commandId, taskId, version, fence, "REQUIREMENT_DELIVERY", "APPROVAL_RESUME",
                0, 3, 0L, ScheduleResourceClass.GENERIC,
                Set.of(ScheduleResourceClass.GENERIC), "project-1", "", "P1",
                "policy-" + taskId, 1L);
    }

    private static RequirementStageCommand policyControlCommand(
            String commandId, String taskId, long version, long fence, String stage, long now
    ) {
        String policyRunId = "POLICY_EVALUATE".equals(stage) ? commandId : "policy-" + taskId;
        return policyControlCommand(commandId, taskId, version, fence, stage, policyRunId, now);
    }

    private static RequirementStageCommand policyControlCommand(
            String commandId, String taskId, long version, long fence, String stage,
            String policyRunId, long now
    ) {
        return RequirementStageCommand.pending(
                commandId, taskId, version, fence, "REQUIREMENT_DELIVERY", stage,
                0, 3, now + 60_000L, ScheduleResourceClass.GENERIC,
                Set.of(ScheduleResourceClass.GENERIC), "project-1", "provider", "P1",
                policyRunId, now);
    }

    private static RequirementPolicyRun policyRun(
            String id, String taskId, String plan, String policy, String action,
            RequirementPolicyRunState state, long sourceVersion, long sourceFence,
            long boundVersion, long boundFence, String consumedCommand, long consumedAt,
            long ledgerVersion, long now
    ) {
        return new RequirementPolicyRun(
                id, taskId, sourceVersion, sourceFence,
                plan, RequirementPolicyRun.canonicalJsonDigest(plan),
                policy, policy.isBlank() ? "" : RequirementPolicyRun.canonicalJsonDigest(policy), action, state,
                boundVersion, boundFence, null, null, "", "", "", 0L, "",
                consumedCommand, consumedAt, ledgerVersion, now, now);
    }

    private static RequirementPolicyRun appliedPolicyRun(
            String taskId, RequirementStageCommand resume, long version, long fence, long now
    ) {
        String planJson = "{}";
        String policyJson = "{\"action\":\"WAITING_APPROVAL\"}";
        return new RequirementPolicyRun(
                "policy-" + taskId,
                taskId,
                resume.taskVersion() - 3L,
                resume.fencingToken() - 3L,
                planJson,
                RequirementPolicyRun.canonicalJsonDigest(planJson),
                policyJson,
                RequirementPolicyRun.canonicalJsonDigest(policyJson),
                "WAITING_APPROVAL",
                RequirementPolicyRunState.APPLIED,
                version,
                fence,
                resume.taskVersion() - 1L,
                resume.fencingToken() - 1L,
                "approval-request-" + taskId,
                "test-approver",
                "",
                now,
                resume.commandId(),
                resume.commandId(),
                now,
                1L,
                now,
                now);
    }

    @Test
    void atomicExhaustionConflictKeepsTheLeaseRecoverableInsteadOfFailingTheTaskWithoutProvenance() {
        long now = System.currentTimeMillis();
        String taskId = "task-atomic-conflict";
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand pending = RequirementStageCommand.pending(
                "cmd-atomic-conflict", taskId, 12L, 13L,
                AgentRole.CODING_AGENT.name(), "ROLE_EXECUTION:CODING_AGENT",
                2, 3, now + 60_000L, ScheduleResourceClass.PROVIDER,
                Set.of(ScheduleResourceClass.PROVIDER), "project-1", "provider", "P1", now);
        commands.enqueue(pending);
        RequirementStageCommand claimed = commands.claim(pending.commandId(), "test-worker", now, 60_000L)
                .orElseThrow();
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        taskStore.saveRequirementTask(RdRequirementTask.created(taskId, new CreateRequirementTaskCommand(
                        "Atomic conflict", "P1", "https://example.invalid/repo.git", "owner", "repo", "main",
                        "deliver", List.of("criterion"), false), now)
                .withState(RdTaskStatus.RECOVERING, "", "{}", "", "recovering", now)
                .withConcurrency(12L, 13L));
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), SnowflakeIdGenerator.defaultGenerator());
        RequirementStageExecutor stageExecutor = mock(RequirementStageExecutor.class);
        when(stageExecutor.plan(any())).thenThrow(new IllegalStateException("role command task is not executing"));
        RequirementStageFinalizationPort finalizer = mock(RequirementStageFinalizationPort.class);
        when(finalizer.prepare(any(), any(), any(), anyLong())).thenAnswer(invocation ->
                RequirementStageFinalization.prepared(
                        invocation.getArgument(0), invocation.getArgument(2), invocation.getArgument(3)));
        when(finalizer.exhaustCommand(any())).thenThrow(
                new IllegalStateException("exhaustion task snapshot mismatch: " + taskId));
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()),
                new InMemoryRequirementDeliveryJobStore(), SnowflakeIdGenerator.defaultGenerator(),
                registry, "test-worker", 3, 60_000L, null, null, commands, stageExecutor,
                finalizer, RequirementDeliverySchedulingPolicy.defaults(), null);

        CompletableFuture<RequirementDeliveryResult> future = new CompletableFuture<>();
        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> invokeRunClaimedCommand(dispatcher, claimed, future));
        assertTrue(conflict.getMessage().contains("exhaustion task snapshot mismatch"));

        RdRequirementTask task = taskStore.findRequirementTask(taskId).orElseThrow();
        assertEquals(RdTaskStatus.RECOVERING, task.status(),
                "CAS/conflict must not compensate with a provenance-less terminal task write");
        assertEquals(12L, task.version());
        assertEquals(13L, task.fencingToken());
        assertEquals(RequirementStageCommand.Status.RUNNING,
                commands.findById(claimed.commandId()).orElseThrow().status());
        assertFalse(future.isDone(), "conflict must leave the caller future reclaimable");
    }

    @Test
    void expiredLeaseAtMaxAttemptsWritesItsExactFailureProvenance() {
        long past = System.currentTimeMillis() - 10_000L;
        String taskId = "task-expired-role-prov";
        String checkpointId = "901";
        String bindingId = "910";
        String commandId = "801";
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        taskStore.saveRequirementTask(RdRequirementTask.created(taskId, new CreateRequirementTaskCommand(
                        "Expired role", "P1", "https://example.invalid/repo.git", "owner", "repo", "main",
                        "deliver", List.of("criterion"), false), past)
                .withState(RdTaskStatus.RECOVERING, "", "{}", "", "recovering", past)
                .withConcurrency(12L, 13L));
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, events, new SnowflakeIdGenerator(1, 1, () -> past + 5_000L));
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        InMemoryTaskRetryAttemptBindingStore bindings = new InMemoryTaskRetryAttemptBindingStore();
        InMemoryTaskRetryFailureProvenanceStore provenanceStore = new InMemoryTaskRetryFailureProvenanceStore();
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        TaskRetryCheckpoint checkpoint = new TaskRetryCheckpoint(
                checkpointId, taskId, TaskFailurePhase.AGENT_ROLE, AgentRole.CODING_AGENT,
                "coding-1", "", "", 1, taskId + ":11:AGENT_ROLE:CODING_AGENT",
                RdTaskStatus.FAILED_NEEDS_HUMAN, 11L, 12L,
                "command-old", "ROLE_EXECUTION:CODING_AGENT", "policy-1", "",
                "sha256:" + "a".repeat(64), "", "", 0L, 0L, "", Long.parseLong(checkpointId),
                "", List.of(), TaskRetryCheckpointStatus.CREATED, "provider missing", "", past, past);
        checkpoints.createOrGet(checkpoint);
        checkpoints.dispatch(checkpointId, 12L, 13L, commandId, past);
        bindings.save(new TaskRetryAttemptBinding(
                bindingId, checkpointId, TaskRetryAttemptKind.AGENT_STAGE, AgentRole.CODING_AGENT,
                "coding-2", "", 2, 0));
        RequirementStageCommand pending = RequirementStageCommand.pending(
                commandId, taskId, 12L, 13L,
                AgentRole.CODING_AGENT.name(), "ROLE_EXECUTION:CODING_AGENT",
                0, 1, past + 1L, ScheduleResourceClass.PROVIDER,
                Set.of(ScheduleResourceClass.PROVIDER), "project-1", "provider", "P1",
                "policy-1", checkpointId, Long.parseLong(checkpointId), bindingId, past);
        commands.enqueue(pending);
        commands.claimBatch("dead-worker", past, 1L, 1);
        InMemoryRequirementStageFinalizationPort finalizer = new InMemoryRequirementStageFinalizationPort(
                commands, new InMemoryRequirementDeliveryJobStore(), taskStore,
                new CoordinatedRdTaskStatePersistence(taskStore, events),
                new SnowflakeIdGenerator(1, 1, () -> past + 5_000L), null, null,
                provenanceStore, checkpoints, bindings, stages);
        RequirementDeliveryJobStore jobs = mock(RequirementDeliveryJobStore.class);
        when(jobs.recoverable(anyLong(), anyInt())).thenReturn(List.of());
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()),
                jobs, SnowflakeIdGenerator.defaultGenerator(), registry, "test-worker", 1, 60_000L,
                null, checkpoints, commands, mock(RequirementStageExecutor.class), finalizer,
                RequirementDeliverySchedulingPolicy.defaults(), null, bindings);

        dispatcher.recover();

        RdRequirementTask task = taskStore.findRequirementTask(taskId).orElseThrow();
        TaskRetryFailureProvenance provenance = provenanceStore.findExact(
                taskId, task.status(), task.version(), task.fencingToken()).orElseThrow();
        assertEquals(RdTaskStatus.DEAD_LETTERED, task.status());
        assertEquals(RequirementStageCommand.Status.DEAD_LETTERED,
                commands.findById(commandId).orElseThrow().status());
        assertEquals(TaskFailurePhase.AGENT_ROLE, provenance.failurePhase());
        assertNotEquals(TaskFailurePhase.CONTEXT, provenance.failurePhase());
        assertEquals("ROLE_EXECUTION:CODING_AGENT", provenance.failedStage());
    }

    @Test
    void executorRejectionAtMaxAttemptsWritesItsExactFailureProvenance() {
        long now = System.currentTimeMillis();
        String taskId = "task-reject-role-prov";
        String checkpointId = "902";
        String bindingId = "912";
        String commandId = "802";
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        taskStore.saveRequirementTask(RdRequirementTask.created(taskId, new CreateRequirementTaskCommand(
                        "Reject role", "P1", "https://example.invalid/repo.git", "owner", "repo", "main",
                        "deliver", List.of("criterion"), false), now)
                .withState(RdTaskStatus.RECOVERING, "", "{}", "", "recovering", now)
                .withConcurrency(12L, 13L));
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, events, SnowflakeIdGenerator.defaultGenerator());
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        InMemoryTaskRetryAttemptBindingStore bindings = new InMemoryTaskRetryAttemptBindingStore();
        InMemoryTaskRetryFailureProvenanceStore provenanceStore = new InMemoryTaskRetryFailureProvenanceStore();
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        TaskRetryCheckpoint checkpoint = new TaskRetryCheckpoint(
                checkpointId, taskId, TaskFailurePhase.AGENT_ROLE, AgentRole.CODING_AGENT,
                "coding-1", "", "", 1, taskId + ":11:AGENT_ROLE:CODING_AGENT",
                RdTaskStatus.FAILED_NEEDS_HUMAN, 11L, 12L,
                "command-old", "ROLE_EXECUTION:CODING_AGENT", "policy-1", "",
                "sha256:" + "a".repeat(64), "", "", 0L, 0L, "", Long.parseLong(checkpointId),
                "", List.of(), TaskRetryCheckpointStatus.CREATED, "provider missing", "", now, now);
        checkpoints.createOrGet(checkpoint);
        checkpoints.dispatch(checkpointId, 12L, 13L, commandId, now);
        bindings.save(new TaskRetryAttemptBinding(
                bindingId, checkpointId, TaskRetryAttemptKind.AGENT_STAGE, AgentRole.CODING_AGENT,
                "coding-2", "", 2, 0));
        RequirementStageCommand pending = RequirementStageCommand.pending(
                commandId, taskId, 12L, 13L,
                AgentRole.CODING_AGENT.name(), "ROLE_EXECUTION:CODING_AGENT",
                0, 1, now + 60_000L, ScheduleResourceClass.PROVIDER,
                Set.of(ScheduleResourceClass.PROVIDER), "project-1", "provider", "P1",
                "policy-1", checkpointId, Long.parseLong(checkpointId), bindingId, now);
        commands.enqueue(pending);
        RequirementStageCommand claimed = commands.claimBatch("test-worker", now, 60_000L, 1).getFirst();
        InMemoryRequirementStageFinalizationPort finalizer = new InMemoryRequirementStageFinalizationPort(
                commands, new InMemoryRequirementDeliveryJobStore(), taskStore,
                new CoordinatedRdTaskStatePersistence(taskStore, events),
                SnowflakeIdGenerator.defaultGenerator(), null, null,
                provenanceStore, checkpoints, bindings, stages);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class),
                new TaskExecutorAdapter(ignored -> { throw new TaskRejectedException("saturated"); }),
                new InMemoryRequirementDeliveryJobStore(), SnowflakeIdGenerator.defaultGenerator(),
                registry, "test-worker", 1, 60_000L, null, checkpoints, commands,
                mock(RequirementStageExecutor.class), finalizer,
                RequirementDeliverySchedulingPolicy.defaults(), null, bindings);

        invokeScheduleClaimedStageCommand(dispatcher, claimed, new CompletableFuture<>());

        RdRequirementTask task = taskStore.findRequirementTask(taskId).orElseThrow();
        TaskRetryFailureProvenance provenance = provenanceStore.findExact(
                taskId, task.status(), task.version(), task.fencingToken()).orElseThrow();
        assertEquals(RdTaskStatus.DEAD_LETTERED, task.status());
        assertEquals(TaskFailurePhase.AGENT_ROLE, provenance.failurePhase());
        assertNotEquals(TaskFailurePhase.CONTEXT, provenance.failurePhase());
        assertEquals("ROLE_EXECUTION:CODING_AGENT", provenance.failedStage());
    }

    @Test
    void checkpointBoundRoleCommandFailingAtMaxAttemptsRoutesThroughExhaustCommandWithAgentRolePhase() {
        long now = System.currentTimeMillis();
        String taskId = "7493233919224057856";
        String checkpointId = "7493236740463923200";
        String bindingId = "7493236740522643456";
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        InMemoryTaskRetryCheckpointStore checkpoints = Mockito.spy(new InMemoryTaskRetryCheckpointStore());
        InMemoryTaskRetryAttemptBindingStore bindings = new InMemoryTaskRetryAttemptBindingStore();
        TaskRetryCheckpoint checkpoint = new TaskRetryCheckpoint(
                checkpointId, taskId, TaskFailurePhase.AGENT_ROLE, AgentRole.CODING_AGENT,
                "7493236740505866240", "", "", 1, taskId + ":11:AGENT_ROLE:CODING_AGENT",
                RdTaskStatus.FAILED_NEEDS_HUMAN, 11L, 12L,
                "7493234773977075712", "ROLE_EXECUTION:CODING_AGENT", "7493234024253624320", "",
                "sha256:" + "a".repeat(64), "", "", 0L, 0L, "",
                Long.parseLong(checkpointId), "", List.of(), TaskRetryCheckpointStatus.CREATED,
                "provider missing", "", now, now);
        checkpoints.createOrGet(checkpoint);
        checkpoints.dispatch(checkpointId, 12L, 13L, "7493236740522643457", now);
        bindings.save(new TaskRetryAttemptBinding(
                bindingId, checkpointId, TaskRetryAttemptKind.AGENT_STAGE, AgentRole.CODING_AGENT,
                "7493236740505866240", "", 2, 0));
        RequirementStageCommand pending = RequirementStageCommand.pending(
                "7493236740522643457", taskId, 12L, 13L,
                AgentRole.CODING_AGENT.name(), "ROLE_EXECUTION:CODING_AGENT",
                2, 3, now + 60_000L, ScheduleResourceClass.PROVIDER,
                Set.of(ScheduleResourceClass.PROVIDER), "project-1", "provider", "P1",
                "7493234024253624320", checkpointId, Long.parseLong(checkpointId), bindingId, now);
        commands.enqueue(pending);
        RequirementStageCommand claimed = commands.claim(pending.commandId(), "test-worker", now, 60_000L)
                .orElseThrow();
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        when(registry.getTask(taskId)).thenReturn(
                requirementTask(taskId, RdTaskStatus.RECOVERING).withConcurrency(12L, 13L));
        RequirementStageExecutor stageExecutor = mock(RequirementStageExecutor.class);
        when(stageExecutor.plan(any())).thenThrow(
                new IllegalStateException("role command task is not executing: " + claimed.commandId()));
        RequirementStageFinalizationPort finalizer = mock(RequirementStageFinalizationPort.class);
        when(finalizer.prepare(any(), any(), any(), anyLong())).thenAnswer(invocation ->
                RequirementStageFinalization.prepared(
                        invocation.getArgument(0), invocation.getArgument(2), invocation.getArgument(3)));
        when(finalizer.exhaustCommand(any())).thenAnswer(invocation -> {
            RequirementStageFinalizationPort.ExhaustionCommand command = invocation.getArgument(0);
            RequirementStageCommand completed = commands.fail(
                    command.stageCommand(), "test-worker", command.errorMessage(), now + 1L);
            return new RequirementStageFinalizationPort.ExhaustionResult(
                    completed,
                    requirementTask(taskId, RdTaskStatus.FAILED_RETRYABLE).withConcurrency(13L, 14L),
                    new com.wish.rd.engine.retry.model.TaskRetryFailureProvenance(
                            command.provenanceDraft().provenanceId(), taskId, completed.commandId(),
                            completed.attemptNo(), command.provenanceDraft().failedStage(),
                            command.provenanceDraft().failurePhase(), RdTaskStatus.FAILED_RETRYABLE,
                            13L, 14L, command.provenanceDraft().failedStageRunId(), "", "",
                            command.provenanceDraft().sourcePolicyRunId(),
                            command.provenanceDraft().sourcePlanDigest(), "",
                            command.provenanceDraft().failureKind(), now + 1L),
                    null);
        });
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()),
                new InMemoryRequirementDeliveryJobStore(), SnowflakeIdGenerator.defaultGenerator(),
                registry, "test-worker", 3, 60_000L, null, checkpoints, commands, stageExecutor,
                finalizer, RequirementDeliverySchedulingPolicy.defaults(), null, bindings);

        CompletableFuture<RequirementDeliveryResult> future = new CompletableFuture<>();
        invokeRunClaimedCommand(dispatcher, claimed, future);

        assertTrue(future.isCompletedExceptionally());
        ArgumentCaptor<RequirementStageFinalizationPort.ExhaustionCommand> captured =
                ArgumentCaptor.forClass(RequirementStageFinalizationPort.ExhaustionCommand.class);
        verify(finalizer).exhaustCommand(captured.capture());
        assertEquals(TaskFailurePhase.AGENT_ROLE, captured.getValue().provenanceDraft().failurePhase());
        assertTrue(captured.getValue().provenanceDraft().failurePhase() != TaskFailurePhase.CONTEXT);
        assertEquals(RdTaskStatus.FAILED_RETRYABLE, captured.getValue().targetTaskStatus());
        verify(checkpoints, never()).findActiveByTask(any());
        verify(finalizer, never()).finalize(any());
    }

    @Test
    void nonFinalTechnicalAttemptKeepsRecoverablePathAndWritesNoProvenance() {
        long now = System.currentTimeMillis();
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        TaskRetryCheckpointStore checkpoints = mock(TaskRetryCheckpointStore.class);
        TaskRetryAttemptBindingStore bindings = mock(TaskRetryAttemptBindingStore.class);
        RequirementStageCommand pending = RequirementStageCommand.pending(
                "non-final-role", "task-non-final", 12L, 13L,
                AgentRole.CODING_AGENT.name(), "ROLE_EXECUTION:CODING_AGENT",
                0, 3, now + 60_000L, ScheduleResourceClass.PROVIDER,
                Set.of(ScheduleResourceClass.PROVIDER), "project-1", "provider", "P1", now);
        commands.enqueue(pending);
        RequirementStageCommand claimed = commands.claim(pending.commandId(), "test-worker", now, 60_000L)
                .orElseThrow();
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        when(registry.getTask(claimed.taskId())).thenReturn(
                requirementTask(claimed.taskId(), RdTaskStatus.RECOVERING).withConcurrency(12L, 13L));
        RequirementStageExecutor stageExecutor = mock(RequirementStageExecutor.class);
        when(stageExecutor.plan(any())).thenThrow(new IllegalStateException("transient provider blip"));
        RequirementStageFinalizationPort finalizer = mock(RequirementStageFinalizationPort.class);
        when(finalizer.prepare(any(), any(), any(), anyLong())).thenAnswer(invocation ->
                RequirementStageFinalization.prepared(
                        invocation.getArgument(0), invocation.getArgument(2), invocation.getArgument(3)));
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                mock(RequirementDeliveryEngine.class), new TaskExecutorAdapter(new SyncTaskExecutor()),
                new InMemoryRequirementDeliveryJobStore(), SnowflakeIdGenerator.defaultGenerator(),
                registry, "test-worker", 3, 60_000L, null, checkpoints, commands, stageExecutor,
                finalizer, RequirementDeliverySchedulingPolicy.defaults(), null, bindings);

        CompletableFuture<RequirementDeliveryResult> future = new CompletableFuture<>();
        invokeRunClaimedCommand(dispatcher, claimed, future);

        assertTrue(future.isCompletedExceptionally());
        assertEquals(RequirementStageCommand.Status.FAILED_RETRYABLE,
                commands.findById(claimed.commandId()).orElseThrow().status());
        verify(finalizer, never()).exhaustCommand(any());
        verify(checkpoints, never()).findActiveByTask(any());
        verify(registry, never()).transitionRequirementFenced(any(), any(), any(), any(), any(), any());
    }

    private static void invokeRunClaimedCommand(
            RequirementDeliveryDispatchService dispatcher,
            RequirementStageCommand command,
            CompletableFuture<RequirementDeliveryResult> future
    ) {
        try {
            Method method = RequirementDeliveryDispatchService.class.getDeclaredMethod(
                    "runClaimedCommand", RequirementStageCommand.class, CompletableFuture.class);
            method.setAccessible(true);
            method.invoke(dispatcher, command, future);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static RequirementStageCommand invokeEnqueueStageCommand(
            RequirementDeliveryDispatchService dispatcher, String taskId
    ) {
        try {
            Method method = RequirementDeliveryDispatchService.class.getDeclaredMethod(
                    "enqueueStageCommand", String.class, long.class);
            method.setAccessible(true);
            return (RequirementStageCommand) method.invoke(dispatcher, taskId, System.currentTimeMillis());
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void invokeScheduleClaimedStageCommand(
            RequirementDeliveryDispatchService dispatcher,
            RequirementStageCommand command,
            CompletableFuture<RequirementDeliveryResult> future
    ) {
        try {
            Method method = RequirementDeliveryDispatchService.class.getDeclaredMethod(
                    "scheduleClaimedStageCommand", RequirementStageCommand.class, CompletableFuture.class);
            method.setAccessible(true);
            method.invoke(dispatcher, command, future);
        } catch (InvocationTargetException exception) {
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void assertNoUmbrellaJobMutation(RequirementDeliveryJobStore jobs) {
        verify(jobs, never()).claim(any(), any(), anyLong(), anyLong());
        verify(jobs, never()).complete(any(), any(), anyLong());
        verify(jobs, never()).fail(any(), any(), any(), anyLong());
        verify(jobs, never()).cancelByTask(any(), any(), anyLong());
    }

    private static final class RecordingPolicyTransactionPort implements RequirementPolicyTransactionPort {

        private final RequirementPolicyResumeResult resumeResult;
        private final RuntimeException failure;
        private final Runnable afterConsume;
        private final AtomicInteger consumeCalls = new AtomicInteger();

        private RecordingPolicyTransactionPort(RequirementPolicyResumeResult resumeResult) {
            this(resumeResult, null);
        }

        private RecordingPolicyTransactionPort(RequirementPolicyResumeResult resumeResult, Runnable afterConsume) {
            this.resumeResult = resumeResult;
            this.failure = null;
            this.afterConsume = afterConsume;
        }

        private RecordingPolicyTransactionPort(RuntimeException failure) {
            this.resumeResult = null;
            this.failure = failure;
            this.afterConsume = null;
        }

        @Override
        public RequirementPolicyApprovalResult approve(
                ApproveRequirementPolicyCommand command, String actor, long nowEpochMillis
        ) {
            throw new UnsupportedOperationException("approval producer is outside this dispatcher test");
        }

        @Override
        public RequirementPolicyResumeResult consumeApproval(
                RequirementStageCommand command, String leaseOwner, long nowEpochMillis
        ) {
            consumeCalls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            if (afterConsume != null) {
                afterConsume.run();
            }
            return resumeResult;
        }
    }

    private static final class RecordingPolicyControlPort implements RequirementPolicyTransactionPort {
        private final RequirementPolicyEvaluationResult evaluationResult;
        private final RequirementPolicyApplyResult applyResult;
        private final AtomicInteger evaluationCalls = new AtomicInteger();
        private final AtomicInteger applyCalls = new AtomicInteger();
        private RecordRequirementPolicyDecisionCommand lastDecision;

        private RecordingPolicyControlPort(
                RequirementPolicyEvaluationResult evaluationResult,
                RequirementPolicyApplyResult applyResult
        ) {
            this.evaluationResult = evaluationResult;
            this.applyResult = applyResult;
        }

        @Override
        public RequirementPolicyEvaluationResult recordEvaluation(
                RecordRequirementPolicyDecisionCommand decision,
                RequirementStageCommand command,
                String leaseOwner,
                long nowEpochMillis
        ) {
            evaluationCalls.incrementAndGet();
            lastDecision = decision;
            return evaluationResult;
        }

        @Override
        public RequirementPolicyApplyResult consumePolicyApply(
                RequirementStageCommand command, String leaseOwner, long nowEpochMillis
        ) {
            applyCalls.incrementAndGet();
            return applyResult;
        }

        @Override
        public RequirementPolicyApprovalResult approve(
                ApproveRequirementPolicyCommand command, String actor, long nowEpochMillis
        ) {
            throw new UnsupportedOperationException("approval producer is outside this dispatcher test");
        }
    }

    private static final class ControllableTaskScheduler implements TaskScheduler {
        private Runnable capturedTask;

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return capture(task);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            return capture(task);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            return capture(task);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return capture(task);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            return capture(task);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return capture(task);
        }

        private ScheduledFuture<?> capture(Runnable task) {
            capturedTask = task;
            return new ControllableScheduledFuture();
        }

        private void runCapturedTaskEvenIfCancelled() {
            if (capturedTask == null) {
                throw new AssertionError("expected the stage heartbeat to be scheduled");
            }
            capturedTask.run();
        }
    }

    private static final class ControllableScheduledFuture implements ScheduledFuture<Object> {
        private boolean cancelled;

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }

    private static final class HeartbeatRacingStageCommandStore implements RequirementStageCommandStore {
        private final InMemoryRequirementStageCommandStore delegate = new InMemoryRequirementStageCommandStore();
        private final AtomicInteger failedCalls = new AtomicInteger();

        @Override
        public RequirementStageCommand enqueue(RequirementStageCommand command) {
            return delegate.enqueue(command);
        }

        @Override
        public Optional<RequirementStageCommand> find(String taskId, String role, String stage) {
            return delegate.find(taskId, role, stage);
        }

        @Override
        public Optional<RequirementStageCommand> findById(String commandId) {
            return delegate.findById(commandId);
        }

        @Override
        public List<RequirementStageCommand> claimBatch(
                String leaseOwner, long nowEpochMillis, long leaseMillis, int batchSize
        ) {
            return delegate.claimBatch(leaseOwner, nowEpochMillis, leaseMillis, batchSize);
        }

        @Override
        public List<RequirementStageCommand> claimFairBatch(
                List<String> commandIds, String leaseOwner, long nowEpochMillis, long leaseMillis,
                RequirementDeliverySchedulingPolicy policy
        ) {
            return delegate.claimFairBatch(commandIds, leaseOwner, nowEpochMillis, leaseMillis, policy);
        }

        @Override
        public Optional<RequirementStageCommand> claim(
                String commandId, String leaseOwner, long nowEpochMillis, long leaseMillis
        ) {
            return delegate.claim(commandId, leaseOwner, nowEpochMillis, leaseMillis);
        }

        @Override
        public RequirementStageCommand heartbeat(
                String commandId, String leaseOwner, long nowEpochMillis, long leaseMillis
        ) {
            throw new IllegalStateException("command is already terminal");
        }

        @Override
        public RequirementStageCommand complete(String commandId, String leaseOwner, long nowEpochMillis) {
            return delegate.complete(commandId, leaseOwner, nowEpochMillis);
        }

        @Override
        public RequirementStageCommand fail(
                String commandId, String leaseOwner, String errorMessage, long nowEpochMillis
        ) {
            failedCalls.incrementAndGet();
            return delegate.fail(commandId, leaseOwner, errorMessage, nowEpochMillis);
        }

        @Override
        public List<RequirementStageCommand> recoverable(long nowEpochMillis, int limit) {
            return delegate.recoverable(nowEpochMillis, limit);
        }

        @Override
        public List<RequirementStageCommand> inFlight(long nowEpochMillis, int limit) {
            return delegate.inFlight(nowEpochMillis, limit);
        }

        @Override
        public List<RequirementStageCommand> deadLetterExpired(long nowEpochMillis, int limit) {
            return delegate.deadLetterExpired(nowEpochMillis, limit);
        }
    }

    private static RagStreamTaskRegistry authoritativeRegistry() {
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        when(registry.getTask(any())).thenAnswer(invocation -> requirementTask(
                invocation.getArgument(0, String.class), RdTaskStatus.CREATED));
        return registry;
    }

    private static RdRequirementTask requirementTask(String taskId, String projectId, RdTaskStatus status) {
        return new RdRequirementTask(
                taskId, "REQUIREMENT", "ADMIN", "source", "", "P1", status,
                "订单状态筛选", projectId, "waimai", "外卖项目", "https://github.com/acme/waimai",
                "acme", "waimai", "main", "feature/status", "支持状态筛选", "[\"筛选正确\"]",
                "prompt", "{}", "", "", 10L, 20L, false).withConcurrency(10L, 20L);
    }

    private static AgentExecutionProfileService profilesFor(String projectId) {
        AgentExecutionProfileService profiles = new AgentExecutionProfileService(
                new InMemoryAgentExecutionProfileStore());
        for (AgentRole role : AgentRole.requirementDeliveryOrder()) {
            String providerId = "provider-" + role.name().toLowerCase(java.util.Locale.ROOT);
            String profileId = "profile-" + role.name().toLowerCase(java.util.Locale.ROOT);
            profiles.register(new AgentExecutionProfile(
                    profileId,
                    projectId,
                    role.name(),
                    role.name(),
                    AgentRuntimeType.PI,
                    providerId,
                    "",
                    "",
                    0L,
                    "tool-policy-" + role.name().toLowerCase(java.util.Locale.ROOT),
                    1L,
                    true,
                    1L
            ));
            profiles.bindProjectDefault(projectId, role.name(), profileId);
        }
        return profiles;
    }

    private static final class CompletionFailingStageCommandStore implements RequirementStageCommandStore {

        private final InMemoryRequirementStageCommandStore delegate;

        private CompletionFailingStageCommandStore(InMemoryRequirementStageCommandStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public RequirementStageCommand enqueue(RequirementStageCommand command) {
            return delegate.enqueue(command);
        }

        @Override
        public Optional<RequirementStageCommand> find(String taskId, String role, String stage) {
            return delegate.find(taskId, role, stage);
        }

        @Override
        public Optional<RequirementStageCommand> findById(String commandId) {
            return delegate.findById(commandId);
        }

        @Override
        public List<RequirementStageCommand> claimBatch(
                String leaseOwner,
                long nowEpochMillis,
                long leaseMillis,
                int batchSize
        ) {
            return delegate.claimBatch(leaseOwner, nowEpochMillis, leaseMillis, batchSize);
        }

        @Override
        public List<RequirementStageCommand> claimFairBatch(
                List<String> commandIds,
                String leaseOwner,
                long nowEpochMillis,
                long leaseMillis,
                RequirementDeliverySchedulingPolicy policy
        ) {
            return delegate.claimFairBatch(commandIds, leaseOwner, nowEpochMillis, leaseMillis, policy);
        }

        @Override
        public Optional<RequirementStageCommand> claim(
                String commandId,
                String leaseOwner,
                long nowEpochMillis,
                long leaseMillis
        ) {
            return delegate.claim(commandId, leaseOwner, nowEpochMillis, leaseMillis);
        }

        @Override
        public RequirementStageCommand heartbeat(
                String commandId,
                String leaseOwner,
                long nowEpochMillis,
                long leaseMillis
        ) {
            return delegate.heartbeat(commandId, leaseOwner, nowEpochMillis, leaseMillis);
        }

        @Override
        public RequirementStageCommand complete(String commandId, String leaseOwner, long nowEpochMillis) {
            throw new IllegalStateException("simulated stage-command completion CAS failure");
        }

        @Override
        public RequirementStageCommand fail(
                String commandId,
                String leaseOwner,
                String errorMessage,
                long nowEpochMillis
        ) {
            return delegate.fail(commandId, leaseOwner, errorMessage, nowEpochMillis);
        }

        @Override
        public List<RequirementStageCommand> recoverable(long nowEpochMillis, int limit) {
            return delegate.recoverable(nowEpochMillis, limit);
        }

        @Override
        public List<RequirementStageCommand> inFlight(long nowEpochMillis, int limit) {
            return delegate.inFlight(nowEpochMillis, limit);
        }

        @Override
        public List<RequirementStageCommand> deadLetterExpired(long nowEpochMillis, int limit) {
            return delegate.deadLetterExpired(nowEpochMillis, limit);
        }
    }
}
