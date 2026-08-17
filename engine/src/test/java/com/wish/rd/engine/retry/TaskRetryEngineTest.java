package com.wish.rd.engine.retry;

import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.recovery.InterruptedStageRecoveryService;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.requirement.review.impl.InMemoryAiReviewRunStore;
import com.wish.rd.engine.requirement.review.model.AiReviewDecision;
import com.wish.rd.engine.requirement.review.model.AiReviewRun;
import com.wish.rd.engine.requirement.review.model.AiReviewRunStatus;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageCommandStore;
import com.wish.rd.engine.retry.impl.InMemoryRequirementRetryDispatchTransactionAdapter;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryAttemptBindingStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryCheckpointStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryFailureProvenanceStore;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryCommand;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskRetryFailureProvenance;
import com.wish.rd.engine.retry.model.TaskRetryPoint;
import com.wish.rd.rag.retrieval.run.model.EvidenceQualityDecision;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;
import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import com.wish.rd.rag.runtime.impl.InMemoryTaskMaterialStore;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskRetryEngineTest {

    private static final String PLAN_DIGEST = "sha256:" + "a".repeat(64);

    @Test
    void rejectsStaleExactCommandStageAndFenceGuardsBeforeCreatingACheckpoint() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        stages.save(stage("coding-1", AgentRole.CODING_AGENT, 1, AgentStageStatus.FAILED_RETRYABLE));
        InMemoryRetrievalRunStore retrievals = new InMemoryRetrievalRunStore();
        InMemoryAiReviewRunStore reviews = new InMemoryAiReviewRunStore();
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        FakeTaskPort tasks = new FakeTaskPort(task(RdTaskStatus.FAILED_NEEDS_HUMAN, "{}"));
        InMemoryTaskRetryFailureProvenanceStore provenance = new InMemoryTaskRetryFailureProvenanceStore();
        provenance.save(new TaskRetryFailureProvenance(
                "provenance-1", "task-1", "command-17", 2,
                "ROLE_EXECUTION:CODING_AGENT", TaskFailurePhase.AGENT_ROLE,
                RdTaskStatus.FAILED_NEEDS_HUMAN, 300L, 400L,
                "coding-1", "", "", "policy-1", "sha256:" + "a".repeat(64), "",
                "TECHNICAL_EXHAUSTED", 500L));
        TaskFailureRecoveryService recovery = new TaskFailureRecoveryService(
                tasks, stages, new InMemoryAgentStageArtifactStore(), retrievals, reviews, checkpoints,
                provenance, new TaskRetryPointResolver(), new TaskFailureDiagnosticParser());
        TaskRetryEngine engine = new TaskRetryEngine(
                tasks, stages, retrievals, reviews, checkpoints, new InMemoryTaskMaterialStore(), recovery,
                new TaskRetryPointResolver(), (taskId, point) -> { }, () -> "retry-1", () -> 1_000L);

        assertThrows(IllegalStateException.class, () -> engine.retry(
                "task-1", "USER", guardedCommand("command-old", "ROLE_EXECUTION:CODING_AGENT", 400L)));
        assertThrows(IllegalStateException.class, () -> engine.retry(
                "task-1", "USER", guardedCommand("command-17", "ROLE_EXECUTION:QA_AGENT", 400L)));
        assertThrows(IllegalStateException.class, () -> engine.retry(
                "task-1", "USER", guardedCommand("command-17", "ROLE_EXECUTION:CODING_AGENT", 399L)));
        assertEquals(List.of(), checkpoints.listByTask("task-1"));
    }

    @Test
    void refusesRetryBeforeWritingWhenCheckpointBoundDispatchPortIsMissing() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        stages.save(stage("coding-1", AgentRole.CODING_AGENT, 1, AgentStageStatus.FAILED_NEEDS_HUMAN));
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        FakeTaskPort tasks = new FakeTaskPort(task(RdTaskStatus.FAILED_NEEDS_HUMAN, "{}"));
        TaskRetryEngine engine = new TaskRetryEngine(
                tasks, stages, new InMemoryRetrievalRunStore(), new InMemoryAiReviewRunStore(), checkpoints,
                new TaskRetryPointResolver(), (taskId, point) -> { }, () -> "101", () -> 1_000L);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> engine.retry("task-1", "USER"));

        assertEquals("checkpoint-bound retry is not configured", failure.getMessage());
        assertEquals(List.of(), checkpoints.listByTask("task-1"));
        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, tasks.task.status());
        assertEquals(List.of(1), attempts(stages, AgentRole.CODING_AGENT));
    }

    @Test
    void previewUsesAuthoritativePublicationProvenanceWhenNoAgentStageFailed() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        InMemoryRetrievalRunStore retrievals = new InMemoryRetrievalRunStore();
        InMemoryAiReviewRunStore reviews = new InMemoryAiReviewRunStore();
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        FakeTaskPort tasks = new FakeTaskPort(task(RdTaskStatus.FAILED_RETRYABLE, "{}"));
        InMemoryTaskRetryFailureProvenanceStore provenance = new InMemoryTaskRetryFailureProvenanceStore();
        String operationId = "sha256:" + "b".repeat(64);
        provenance.save(new TaskRetryFailureProvenance(
                "provenance-pub", "task-1", "command-pub", 3,
                "PUBLICATION:" + operationId, TaskFailurePhase.PR_PUBLICATION,
                RdTaskStatus.FAILED_RETRYABLE, 300L, 400L,
                "", "", "", "policy-1", "sha256:" + "a".repeat(64), operationId,
                "UNKNOWN_REMOTE_RESULT", 500L));
        TaskFailureRecoveryService recovery = new TaskFailureRecoveryService(
                tasks, stages, new InMemoryAgentStageArtifactStore(), retrievals, reviews, checkpoints,
                provenance, new TaskRetryPointResolver(), new TaskFailureDiagnosticParser());
        TaskRetryEngine engine = new TaskRetryEngine(
                tasks, stages, retrievals, reviews, checkpoints, new InMemoryTaskMaterialStore(), recovery,
                new TaskRetryPointResolver(), (taskId, point) -> { }, () -> "retry-1", () -> 1_000L);

        var point = engine.preview("task-1");

        assertEquals(TaskFailurePhase.PR_PUBLICATION, point.failurePhase());
        assertEquals("PUBLICATION:" + operationId, point.failedStage());
        assertEquals(operationId, point.publicationOperationId());
        assertThrows(IllegalStateException.class, () -> new TaskRetryPointResolver().resolve(
                tasks.getRequirementTask("task-1"), List.of(), List.of(), List.of()));
    }

    @Test
    void previewUsesAuthoritativeHostVerifyProvenanceInsteadOfErrorText() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        stages.save(stage("coding-1", AgentRole.CODING_AGENT, 1, AgentStageStatus.SUCCEEDED));
        InMemoryRetrievalRunStore retrievals = new InMemoryRetrievalRunStore();
        InMemoryAiReviewRunStore reviews = new InMemoryAiReviewRunStore();
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        FakeTaskPort tasks = new FakeTaskPort(task(RdTaskStatus.FAILED_NEEDS_HUMAN,
                "{\"errorMessage\":\"QA failed; publication blocked\"}"));
        InMemoryTaskRetryFailureProvenanceStore provenance = new InMemoryTaskRetryFailureProvenanceStore();
        provenance.save(new TaskRetryFailureProvenance(
                "provenance-verify", "task-1", "command-verify", 1,
                "HOST_VERIFY", TaskFailurePhase.HOST_VERIFY,
                RdTaskStatus.FAILED_NEEDS_HUMAN, 300L, 400L,
                "", "", "", "policy-1", PLAN_DIGEST, "",
                "PRODUCT_DEFECT", 500L, "verify-9"));
        TaskFailureRecoveryService recovery = new TaskFailureRecoveryService(
                tasks, stages, new InMemoryAgentStageArtifactStore(), retrievals, reviews, checkpoints,
                provenance, new TaskRetryPointResolver(), new TaskFailureDiagnosticParser());
        TaskRetryEngine engine = new TaskRetryEngine(
                tasks, stages, retrievals, reviews, checkpoints, new InMemoryTaskMaterialStore(), recovery,
                new TaskRetryPointResolver(), (taskId, point) -> { }, () -> "retry-1", () -> 1_000L);

        TaskRetryPoint preview = engine.preview("task-1");
        TaskRetryPoint snapshot = recovery.snapshot("task-1").retryPoint();

        assertEquals(TaskFailurePhase.HOST_VERIFY, preview.failurePhase());
        assertEquals(AgentRole.CODING_AGENT, preview.retryFromRole());
        assertEquals(snapshot.failurePhase(), preview.failurePhase());
        assertEquals(snapshot.retryFromRole(), preview.retryFromRole());
        assertEquals(snapshot.failedStageCommandId(), preview.failedStageCommandId());
    }

    @Test
    void createsNewAttemptsFromFailedCodingRoleAndDispatchesOnce() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        stages.save(stage("reviewer-1", AgentRole.REQUIREMENT_REVIEWER, 1, AgentStageStatus.SUCCEEDED));
        stages.save(stage("architect-1", AgentRole.SOLUTION_ARCHITECT, 1, AgentStageStatus.SUCCEEDED));
        stages.save(stage("coding-1", AgentRole.CODING_AGENT, 1, AgentStageStatus.FAILED_NEEDS_HUMAN));
        stages.save(stage("qa-1", AgentRole.QA_AGENT, 1, AgentStageStatus.PENDING));
        FakeTaskPort tasks = new FakeTaskPort(task(RdTaskStatus.FAILED_NEEDS_HUMAN, "{}"));
        List<String> dispatched = new ArrayList<>();
        AtomicInteger ids = new AtomicInteger();
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        TaskRetryEngine engine = engine(tasks, stages, checkpoints, dispatched, ids);

        var first = engine.retry("task-1", "USER");
        var duplicate = engine.retry("task-1", "USER");

        assertSame(first, duplicate, "duplicate click must reuse the active checkpoint");
        assertEquals(TaskRetryCheckpointStatus.DISPATCHED, first.status());
        assertEquals(List.of(first.checkpointId()), dispatched);
        assertEquals(List.of(1), attempts(stages, AgentRole.REQUIREMENT_REVIEWER));
        assertEquals(List.of(1), attempts(stages, AgentRole.SOLUTION_ARCHITECT));
        assertEquals(List.of(1, 2), attempts(stages, AgentRole.CODING_AGENT));
        assertEquals(List.of(1), attempts(stages, AgentRole.QA_AGENT));
        assertEquals(RdTaskStatus.RECOVERING, tasks.task.status());
    }

    @Test
    void shouldRecoverSettledWorkspaceWithoutCreatingFreshAttemptOnRetry() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        InMemoryAgentStageArtifactStore artifacts = new InMemoryAgentStageArtifactStore();
        stages.save(stage("reviewer-1", AgentRole.REQUIREMENT_REVIEWER, 1, AgentStageStatus.SUCCEEDED));
        stages.save(stage("architect-1", AgentRole.SOLUTION_ARCHITECT, 1, AgentStageStatus.SUCCEEDED));
        stages.save(stage("coding-1", AgentRole.CODING_AGENT, 1, AgentStageStatus.FAILED_NEEDS_HUMAN));
        stages.save(stage("coding-2", AgentRole.CODING_AGENT, 2, AgentStageStatus.RUNNING));
        stages.save(stage("qa-1", AgentRole.QA_AGENT, 1, AgentStageStatus.PENDING));
        FakeTaskPort tasks = new FakeTaskPort(task(RdTaskStatus.FAILED_NEEDS_HUMAN, "{}"));
        TaskRetryEngine engine = engine(
                tasks,
                stages,
                new InMemoryTaskRetryCheckpointStore(),
                new ArrayList<>(),
                new AtomicInteger()
        );
        engine.setInterruptedStageRecoveryService(new InterruptedStageRecoveryService(
                interrupted -> java.util.Optional.of(new com.wish.rd.engine.agent.recovery.model.RecoveredWorkspaceExecution(
                        true,
                        "{\"status\":\"SUCCESS\",\"summary\":\"coding recovered\"}",
                        "",
                        "",
                        "pi-opencode",
                        "[]"
                )),
                stages,
                artifacts,
                () -> "artifact-1"
        ));

        engine.retry("task-1", "USER");

        assertEquals(List.of(1, 2), attempts(stages, AgentRole.CODING_AGENT));
        assertEquals(AgentStageStatus.SUCCEEDED, stages.findById("coding-2").orElseThrow().status());
        assertFalse(stages.findById("coding-2").orElseThrow().resultArtifactId().isBlank());
    }

    @Test
    void replacesInterruptedRunningAttemptBeforeRetryingItsRole() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        stages.save(stage("reviewer-1", AgentRole.REQUIREMENT_REVIEWER, 1, AgentStageStatus.SUCCEEDED));
        stages.save(stage("architect-1", AgentRole.SOLUTION_ARCHITECT, 1, AgentStageStatus.SUCCEEDED));
        stages.save(stage("coding-1", AgentRole.CODING_AGENT, 1, AgentStageStatus.FAILED_NEEDS_HUMAN));
        stages.save(stage("coding-2", AgentRole.CODING_AGENT, 2, AgentStageStatus.RUNNING));
        stages.save(stage("qa-1", AgentRole.QA_AGENT, 1, AgentStageStatus.PENDING));
        FakeTaskPort tasks = new FakeTaskPort(task(RdTaskStatus.FAILED_NEEDS_HUMAN, "{}"));
        TaskRetryEngine engine = engine(
                tasks,
                stages,
                new InMemoryTaskRetryCheckpointStore(),
                new ArrayList<>(),
                new AtomicInteger()
        );

        engine.retry("task-1", "USER");

        assertEquals(List.of(1, 2, 3), attempts(stages, AgentRole.CODING_AGENT));
        AgentStageRun interrupted = stages.findById("coding-2").orElseThrow();
        assertEquals(AgentStageStatus.FAILED_RETRYABLE, interrupted.status());
        assertEquals("ORCHESTRATION_INTERRUPTED", interrupted.errorCategory());
        assertEquals(AgentStageStatus.PENDING, latestStage(stages, AgentRole.CODING_AGENT).status());
    }

    @Test
    void manuallyRecoversDeadLetteredTaskFromItsInterruptedRole() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        stages.save(stage("reviewer-1", AgentRole.REQUIREMENT_REVIEWER, 1, AgentStageStatus.SUCCEEDED));
        stages.save(stage("architect-1", AgentRole.SOLUTION_ARCHITECT, 1, AgentStageStatus.SUCCEEDED));
        stages.save(stage("coding-1", AgentRole.CODING_AGENT, 1, AgentStageStatus.FAILED_NEEDS_HUMAN));
        stages.save(stage("coding-2", AgentRole.CODING_AGENT, 2, AgentStageStatus.RUNNING));
        stages.save(stage("qa-1", AgentRole.QA_AGENT, 1, AgentStageStatus.PENDING));
        FakeTaskPort tasks = new FakeTaskPort(task(RdTaskStatus.DEAD_LETTERED, """
                {"failurePhase":"AGENT_ROLE","errorCategory":"IllegalStateException"}
                """));
        List<String> dispatched = new ArrayList<>();
        TaskRetryEngine engine = engine(
                tasks,
                stages,
                new InMemoryTaskRetryCheckpointStore(),
                dispatched,
                new AtomicInteger()
        );

        var checkpoint = engine.retry("task-1", "USER");

        assertEquals(TaskRetryCheckpointStatus.DISPATCHED, checkpoint.status());
        assertEquals(RdTaskStatus.RECOVERING, tasks.task.status());
        assertEquals(List.of(checkpoint.checkpointId()), dispatched);
        assertEquals(List.of(1, 2, 3), attempts(stages, AgentRole.CODING_AGENT));
        assertEquals(AgentStageStatus.FAILED_RETRYABLE,
                stages.findById("coding-2").orElseThrow().status());
        assertEquals(AgentStageStatus.PENDING, latestStage(stages, AgentRole.CODING_AGENT).status());
    }

    @Test
    void manuallyRecoversCancelledCodingAttemptWithoutReplayingUpstreamRoles() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        stages.save(stage("reviewer-1", AgentRole.REQUIREMENT_REVIEWER, 1, AgentStageStatus.SUCCEEDED));
        stages.save(stage("architect-1", AgentRole.SOLUTION_ARCHITECT, 1, AgentStageStatus.SUCCEEDED));
        stages.save(stage("coding-3", AgentRole.CODING_AGENT, 3, AgentStageStatus.CANCELLED));
        stages.save(stage("qa-1", AgentRole.QA_AGENT, 1, AgentStageStatus.CANCELLED));
        FakeTaskPort tasks = new FakeTaskPort(task(RdTaskStatus.CANCELLED, "{}"));
        List<String> dispatched = new ArrayList<>();
        TaskRetryEngine engine = engine(
                tasks,
                stages,
                new InMemoryTaskRetryCheckpointStore(),
                dispatched,
                new AtomicInteger()
        );

        var checkpoint = engine.retry("task-1", "USER");

        assertEquals(TaskRetryCheckpointStatus.DISPATCHED, checkpoint.status());
        assertEquals(AgentRole.CODING_AGENT, checkpoint.retryFromRole());
        assertEquals("coding-3", checkpoint.failedStageRunId());
        assertEquals(RdTaskStatus.RECOVERING, tasks.task.status());
        assertEquals(List.of(checkpoint.checkpointId()), dispatched);
        assertEquals(List.of(1), attempts(stages, AgentRole.REQUIREMENT_REVIEWER));
        assertEquals(List.of(1), attempts(stages, AgentRole.SOLUTION_ARCHITECT));
        assertEquals(List.of(3, 4), attempts(stages, AgentRole.CODING_AGENT));
        assertEquals(List.of(1, 2), attempts(stages, AgentRole.QA_AGENT));
        assertEquals(AgentStageStatus.PENDING, latestStage(stages, AgentRole.CODING_AGENT).status());
    }

    @Test
    void rejectsAutomaticRecoveryOfCancelledTask() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        stages.save(stage("coding-1", AgentRole.CODING_AGENT, 1, AgentStageStatus.CANCELLED));
        TaskRetryEngine engine = engine(
                new FakeTaskPort(task(RdTaskStatus.CANCELLED, "{}")),
                stages,
                new InMemoryTaskRetryCheckpointStore(),
                new ArrayList<>(),
                new AtomicInteger()
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> engine.retry("task-1", "SYSTEM")
        );

        assertEquals("cancelled task recovery requires an operator request", failure.getMessage());
    }

    @Test
    void createsNewAttemptForTerminalDownstreamRole() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        stages.save(stage("reviewer-1", AgentRole.REQUIREMENT_REVIEWER, 1, AgentStageStatus.SUCCEEDED));
        stages.save(stage("architect-1", AgentRole.SOLUTION_ARCHITECT, 1, AgentStageStatus.SUCCEEDED));
        stages.save(stage("coding-1", AgentRole.CODING_AGENT, 1, AgentStageStatus.FAILED_NEEDS_HUMAN));
        stages.save(stage("qa-1", AgentRole.QA_AGENT, 1, AgentStageStatus.SUCCEEDED));
        FakeTaskPort tasks = new FakeTaskPort(task(RdTaskStatus.FAILED_NEEDS_HUMAN, "{}"));
        TaskRetryEngine engine = engine(
                tasks,
                stages,
                new InMemoryTaskRetryCheckpointStore(),
                new ArrayList<>(),
                new AtomicInteger()
        );

        engine.retry("task-1", "USER");

        assertEquals(List.of(1, 2), attempts(stages, AgentRole.CODING_AGENT));
        assertEquals(List.of(1, 2), attempts(stages, AgentRole.QA_AGENT));
    }

    @Test
    void bouncesQaFailureBackToCodingWhenTheOperatorOverridesTheRetryRole() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        stages.save(stage("reviewer-1", AgentRole.REQUIREMENT_REVIEWER, 1, AgentStageStatus.SUCCEEDED));
        stages.save(stage("architect-1", AgentRole.SOLUTION_ARCHITECT, 1, AgentStageStatus.SUCCEEDED));
        stages.save(stage("coding-1", AgentRole.CODING_AGENT, 1, AgentStageStatus.SUCCEEDED));
        stages.save(stage("qa-1", AgentRole.QA_AGENT, 1, AgentStageStatus.FAILED_NEEDS_HUMAN));
        FakeTaskPort tasks = new FakeTaskPort(task(RdTaskStatus.FAILED_NEEDS_HUMAN, "{}"));
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        List<String> dispatched = new ArrayList<>();
        TaskRetryEngine engine = engine(tasks, stages, checkpoints, dispatched, new AtomicInteger());

        var checkpoint = engine.retry("task-1", "USER", new TaskRetryCommand(
                "qa-1", "", "", 0L, "浏览器验证发现对话框无法打开，需要修改交互实现", List.of(),
                AgentRole.CODING_AGENT.name()));

        assertEquals(AgentRole.CODING_AGENT, checkpoint.retryFromRole());
        assertEquals("qa-1", checkpoint.failedStageRunId());
        assertEquals(TaskRetryCheckpointStatus.DISPATCHED, checkpoint.status());
        assertEquals(List.of(checkpoint.checkpointId()), dispatched);
        // 打回后 CODING 与 QA 都必须重开新 attempt，上游评审/架构保持复用。
        assertEquals(List.of(1), attempts(stages, AgentRole.REQUIREMENT_REVIEWER));
        assertEquals(List.of(1), attempts(stages, AgentRole.SOLUTION_ARCHITECT));
        assertEquals(List.of(1, 2), attempts(stages, AgentRole.CODING_AGENT));
        assertEquals(List.of(1, 2), attempts(stages, AgentRole.QA_AGENT));
    }

    @Test
    void rejectsARetryRoleOverrideDownstreamOfTheFailedRole() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        stages.save(stage("reviewer-1", AgentRole.REQUIREMENT_REVIEWER, 1, AgentStageStatus.SUCCEEDED));
        stages.save(stage("architect-1", AgentRole.SOLUTION_ARCHITECT, 1, AgentStageStatus.SUCCEEDED));
        stages.save(stage("coding-1", AgentRole.CODING_AGENT, 1, AgentStageStatus.FAILED_NEEDS_HUMAN));
        stages.save(stage("qa-1", AgentRole.QA_AGENT, 1, AgentStageStatus.PENDING));
        FakeTaskPort tasks = new FakeTaskPort(task(RdTaskStatus.FAILED_NEEDS_HUMAN, "{}"));
        TaskRetryEngine engine = engine(tasks, stages, new InMemoryTaskRetryCheckpointStore(),
                new ArrayList<>(), new AtomicInteger());

        assertThrows(IllegalArgumentException.class, () -> engine.retry("task-1", "USER",
                new TaskRetryCommand("coding-1", "", "", 0L, "", List.of(), AgentRole.QA_AGENT.name())));
        assertThrows(IllegalArgumentException.class, () -> engine.retry("task-1", "USER",
                new TaskRetryCommand("coding-1", "", "", 0L, "", List.of(), "NOT_A_ROLE")));
    }

    @Test
    void keepsDispatchedCheckpointWhenSchedulingFailsAfterInitialization() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        stages.save(stage("reviewer-1", AgentRole.REQUIREMENT_REVIEWER, 1, AgentStageStatus.SUCCEEDED));
        stages.save(stage("architect-1", AgentRole.SOLUTION_ARCHITECT, 1, AgentStageStatus.SUCCEEDED));
        stages.save(stage("coding-1", AgentRole.CODING_AGENT, 1, AgentStageStatus.FAILED_NEEDS_HUMAN));
        stages.save(stage("qa-1", AgentRole.QA_AGENT, 1, AgentStageStatus.PENDING));
        FakeTaskPort tasks = new FakeTaskPort(task(RdTaskStatus.FAILED_NEEDS_HUMAN, "{}"));
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        List<String> dispatched = new ArrayList<>();
        TaskRetryEngine engine = checkpointBoundEngine(
                tasks, stages, new InMemoryRetrievalRunStore(), new InMemoryAiReviewRunStore(),
                checkpoints, dispatched, new AtomicInteger(),
                inferredAgentRoleProvenance(tasks.task, stages),
                checkpointId -> {
                    dispatched.add(checkpointId);
                    throw new IllegalStateException("queue unavailable");
                });

        var checkpoint = engine.retry("task-1", "USER");

        assertEquals(TaskRetryCheckpointStatus.DISPATCHED, checkpoint.status());
        assertEquals(RdTaskStatus.RECOVERING, tasks.task.status());
        assertEquals(List.of(checkpoint.checkpointId()), dispatched);
        assertEquals(AgentStageStatus.PENDING, latestStage(stages, AgentRole.CODING_AGENT).status());
        assertSame(checkpoint, engine.retry("task-1", "USER"));
    }

    @Test
    void prPublicationRetryDoesNotCreateRoleAttempts() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        for (AgentRole role : AgentRole.requirementDeliveryOrder()) {
            stages.save(stage(role.name().toLowerCase() + "-1", role, 1, AgentStageStatus.SUCCEEDED));
        }
        FakeTaskPort tasks = new FakeTaskPort(task(RdTaskStatus.REJECTED, """
                {"pullRequestPublication":{"success":false,"errorMessage":"unavailable"}}
                """));
        List<String> dispatched = new ArrayList<>();
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        TaskRetryEngine engine = checkpointBoundEngine(
                tasks, stages, new InMemoryRetrievalRunStore(), new InMemoryAiReviewRunStore(),
                checkpoints, dispatched, new AtomicInteger(),
                publicationProvenance(tasks.task, "operation-1"));

        var checkpoint = engine.retry("task-1", "USER");

        for (AgentRole role : AgentRole.requirementDeliveryOrder()) {
            assertEquals(List.of(1), attempts(stages, role));
        }
        assertEquals(List.of(checkpoint.checkpointId()), dispatched);
        assertEquals("operation-1", checkpoint.publicationOperationId());
    }

    @Test
    void requiresSupplementForNeedInfoAndPersistsSelectedTaskEvidence() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        InMemoryAgentStageArtifactStore artifacts = new InMemoryAgentStageArtifactStore();
        InMemoryTaskMaterialStore materials = new InMemoryTaskMaterialStore();
        AgentStageRun failed = stage("reviewer-1", AgentRole.REQUIREMENT_REVIEWER, 1,
                AgentStageStatus.FAILED_NEEDS_HUMAN).withResultArtifactId("result-review-1", 120L);
        stages.save(failed);
        artifacts.save(new AgentStageArtifact(
                "result-review-1", failed.stageRunId(), "task-1", AgentRole.REQUIREMENT_REVIEWER,
                "RESULT_JSON", "", "review", "{\"decision\":\"NEED_INFO\",\"missingInformation\":[\"确认账号\"]}",
                "sha256:review", "{}", 120L
        ));
        materials.save(new TaskMaterial(
                "material-1", "task-1", TaskMaterialType.REQUIREMENT_DOC, TaskMaterialSourceType.MANUAL_TEXT,
                "账号澄清", "", "text/plain", "sha256:material", "顾客使用 user1", "", "", "", "{}", 100L, 100L
        ));
        FakeTaskPort tasks = new FakeTaskPort(task(RdTaskStatus.FAILED_NEEDS_HUMAN, "{}"));
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        TaskRetryEngine engine = evidenceAwareEngine(tasks, stages, artifacts, materials, checkpoints);

        assertThrows(IllegalArgumentException.class,
                () -> engine.retry("task-1", "USER", new TaskRetryCommand("reviewer-1", "", List.of())));

        var checkpoint = engine.retry("task-1", "USER", new TaskRetryCommand(
                "reviewer-1", "顾客账号使用 user1", List.of("material-1")));

        assertEquals("顾客账号使用 user1", checkpoint.operatorNote());
        assertEquals(List.of("material-1"), checkpoint.evidenceMaterialIds());
        assertEquals(List.of(1, 2), attempts(stages, AgentRole.REQUIREMENT_REVIEWER));
        assertEquals(List.of(1), attempts(stages, AgentRole.SOLUTION_ARCHITECT));
        assertSame(checkpoint, engine.retry("task-1", "USER", new TaskRetryCommand(
                "reviewer-1", "顾客账号使用 user1", List.of("material-1"))));
        assertThrows(IllegalStateException.class, () -> engine.retry(
                "task-1", "USER", new TaskRetryCommand(
                        "reviewer-1", "改用另一个账号", List.of("material-1"))));
    }

    @Test
    void rejectsRetryWhenTheDisplayedAiReviewFailureIsStale() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        InMemoryAiReviewRunStore reviews = new InMemoryAiReviewRunStore();
        reviews.create(new AiReviewRun(
                "review-1", "task-1", 1, "", AiReviewRunStatus.SUCCEEDED_NOT_OK,
                "review-model", "sha256:package", AiReviewDecision.NOT_OK, 40,
                AgentRole.CODING_AGENT.name(), "代码变更不满足验收", "", "",
                1L, 100L, 500L
        ));
        FakeTaskPort tasks = new FakeTaskPort(task(RdTaskStatus.REJECTED, "{}"));
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        List<String> dispatched = new ArrayList<>();
        TaskRetryEngine engine = checkpointBoundEngine(
                tasks, stages, new InMemoryRetrievalRunStore(), reviews, checkpoints,
                dispatched, new AtomicInteger(),
                new TaskRetryFailureProvenance(
                        "provenance-ai", "task-1", "command-ai", 1, "AI_REVIEW",
                        TaskFailurePhase.AI_REVIEW, RdTaskStatus.REJECTED, 300L, 400L,
                        "", "", "review-1", "policy-1", PLAN_DIGEST, "",
                        "TECHNICAL", 500L));

        assertThrows(IllegalStateException.class, () -> engine.retry(
                "task-1", "USER", new TaskRetryCommand("", "", "review-old", 300L, "", List.of())
        ));
        var checkpoint = engine.retry(
                "task-1", "USER", new TaskRetryCommand("", "", "review-1", 300L, "", List.of())
        );

        assertEquals("review-1", checkpoint.failedAiReviewRunId());
        assertSame(checkpoint, engine.retry(
                "task-1", "USER", new TaskRetryCommand("", "", "review-1", 300L, "", List.of())
        ));
        assertThrows(IllegalStateException.class, () -> engine.retry(
                "task-1", "USER", new TaskRetryCommand("", "", "review-old", 300L, "", List.of())
        ));
    }

    @Test
    void rejectsRetryWhenTheDisplayedRagFailureIsStale() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        InMemoryRetrievalRunStore retrievals = new InMemoryRetrievalRunStore();
        RetrievalRun failed = retrievals.create(retrievalRun("retrieval-1"));
        failed = retrievals.transition(
                failed.runId(), failed.version(), RetrievalRunStatus.PLANNING, 0,
                null, "", "", "", "SYSTEM", 101L
        );
        retrievals.transition(
                failed.runId(), failed.version(), RetrievalRunStatus.FAILED_RETRYABLE, 0,
                null, "provider unavailable", "PROVIDER", "timeout", "SYSTEM", 102L
        );
        FakeTaskPort tasks = new FakeTaskPort(task(RdTaskStatus.FAILED_RETRYABLE, "{}"));
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        List<String> dispatched = new ArrayList<>();
        TaskRetryEngine engine = checkpointBoundEngine(
                tasks, stages, retrievals, new InMemoryAiReviewRunStore(), checkpoints,
                dispatched, new AtomicInteger(),
                new TaskRetryFailureProvenance(
                        "provenance-rag", "task-1", "command-rag", 1,
                        "ROLE_EXECUTION:CODING_AGENT", TaskFailurePhase.RAG,
                        RdTaskStatus.FAILED_RETRYABLE, 300L, 400L,
                        "", "retrieval-1", "", "policy-1", PLAN_DIGEST, "",
                        "TECHNICAL", 500L));

        assertThrows(IllegalStateException.class, () -> engine.retry(
                "task-1", "USER", new TaskRetryCommand("", "retrieval-old", 300L, "", List.of())
        ));
        assertThrows(IllegalStateException.class, () -> engine.retry(
                "task-1", "USER", new TaskRetryCommand("", "retrieval-1", 299L, "", List.of())
        ));

        var checkpoint = engine.retry(
                "task-1", "USER", new TaskRetryCommand("", "retrieval-1", 300L, "", List.of())
        );
        assertEquals("retrieval-1", checkpoint.failedRetrievalRunId());
        assertEquals(1, retrievals.listByTask("task-1").size());
        assertSame(checkpoint, engine.retry(
                "task-1", "USER", new TaskRetryCommand("", "retrieval-1", 300L, "", List.of())
        ));
        assertThrows(IllegalStateException.class, () -> engine.retry(
                "task-1", "USER", new TaskRetryCommand("", "retrieval-old", 300L, "", List.of())
        ));
        assertThrows(IllegalStateException.class, () -> engine.retry(
                "task-1", "USER", new TaskRetryCommand("", "retrieval-1", 299L, "", List.of())
        ));
    }

    private TaskRetryEngine engine(
            FakeTaskPort tasks,
            InMemoryAgentStageRunStore stages,
            InMemoryTaskRetryCheckpointStore checkpoints,
            List<String> dispatched,
            AtomicInteger ids
    ) {
        return checkpointBoundEngine(
                tasks, stages, new InMemoryRetrievalRunStore(), new InMemoryAiReviewRunStore(),
                checkpoints, dispatched, ids, inferredAgentRoleProvenance(tasks.task, stages));
    }

    private TaskRetryEngine checkpointBoundEngine(
            FakeTaskPort tasks,
            InMemoryAgentStageRunStore stages,
            InMemoryRetrievalRunStore retrievals,
            InMemoryAiReviewRunStore reviews,
            InMemoryTaskRetryCheckpointStore checkpoints,
            List<String> dispatched,
            AtomicInteger ids,
            TaskRetryFailureProvenance provenance
    ) {
        return checkpointBoundEngine(tasks, stages, retrievals, reviews, checkpoints, dispatched, ids,
                provenance, dispatched::add);
    }

    private TaskRetryEngine checkpointBoundEngine(
            FakeTaskPort tasks,
            InMemoryAgentStageRunStore stages,
            InMemoryRetrievalRunStore retrievals,
            InMemoryAiReviewRunStore reviews,
            InMemoryTaskRetryCheckpointStore checkpoints,
            List<String> dispatched,
            AtomicInteger ids,
            TaskRetryFailureProvenance provenance,
            Consumer<String> onDispatchCheckpoint
    ) {
        InMemoryTaskRetryFailureProvenanceStore provenanceStore = new InMemoryTaskRetryFailureProvenanceStore();
        provenanceStore.save(provenance);
        TaskFailureRecoveryService recovery = new TaskFailureRecoveryService(
                tasks, stages, new InMemoryAgentStageArtifactStore(), retrievals, reviews, checkpoints,
                provenanceStore, new TaskRetryPointResolver(), new TaskFailureDiagnosticParser());
        InMemoryTaskRetryAttemptBindingStore bindings = new InMemoryTaskRetryAttemptBindingStore();
        TaskRetryEngine engine = new TaskRetryEngine(
                tasks, stages, retrievals, reviews, checkpoints, new InMemoryTaskMaterialStore(), recovery,
                new TaskRetryPointResolver(), new TaskRetryDispatcherPort() {
                    @Override
                    public void dispatch(String taskId, TaskRetryPoint retryPoint) {
                        throw new IllegalStateException(
                                "retry dispatch requires an exact durable checkpoint route: " + taskId);
                    }

                    @Override
                    public void dispatchCheckpoint(String checkpointId) {
                        onDispatchCheckpoint.accept(checkpointId);
                    }
                },
                () -> Integer.toString(ids.incrementAndGet()),
                () -> 1_000L + ids.get());
        engine.setTaskRetryAttemptBindingStore(bindings);
        engine.setRequirementRetryDispatchTransactionPort(new InMemoryRequirementRetryDispatchTransactionAdapter(
                tasks, checkpoints, new InMemoryRequirementStageCommandStore(), bindings));
        return engine;
    }

    private TaskRetryFailureProvenance inferredAgentRoleProvenance(
            RdRequirementTask task, InMemoryAgentStageRunStore stages) {
        List<AgentRole> order = AgentRole.requirementDeliveryOrder();
        AgentStageRun failed = stages.listByTask(task.taskId()).stream()
                .filter(this::isRetryableStage)
                .min(Comparator.comparingInt(stage -> order.indexOf(stage.role())))
                .orElseThrow(() -> new IllegalStateException("test fixture has no retryable stage"));
        return agentRoleProvenance(task, failed.stageRunId(), failed.role());
    }

    private boolean isRetryableStage(AgentStageRun stage) {
        return stage.status() == AgentStageStatus.FAILED_NEEDS_HUMAN
                || stage.status() == AgentStageStatus.FAILED_RETRYABLE
                || stage.status() == AgentStageStatus.CANCELLED
                || stage.status() == AgentStageStatus.RUNNING;
    }

    private TaskRetryFailureProvenance agentRoleProvenance(
            RdRequirementTask task, String stageRunId, AgentRole role) {
        return new TaskRetryFailureProvenance(
                "provenance-1", task.taskId(), "command-1", 1,
                "ROLE_EXECUTION:" + role.name(), TaskFailurePhase.AGENT_ROLE,
                task.status(), task.version(), task.fencingToken(),
                stageRunId, "", "", "policy-1", PLAN_DIGEST, "",
                "TECHNICAL", 500L);
    }

    private TaskRetryFailureProvenance publicationProvenance(RdRequirementTask task, String operationId) {
        return new TaskRetryFailureProvenance(
                "provenance-pub", task.taskId(), "command-pub", 1,
                "PUBLICATION:" + operationId, TaskFailurePhase.PR_PUBLICATION,
                task.status(), task.version(), task.fencingToken(),
                "", "", "", "policy-1", PLAN_DIGEST, operationId,
                "UNKNOWN_REMOTE_RESULT", 500L);
    }

    private List<Integer> attempts(InMemoryAgentStageRunStore stages, AgentRole role) {
        return stages.listByTask("task-1").stream().filter(stage -> stage.role() == role)
                .map(AgentStageRun::attemptNo).sorted().toList();
    }

    private AgentStageRun latestStage(InMemoryAgentStageRunStore stages, AgentRole role) {
        return stages.listByTask("task-1").stream()
                .filter(stage -> stage.role() == role)
                .max(Comparator.comparingInt(AgentStageRun::attemptNo))
                .orElseThrow();
    }

    private TaskRetryEngine evidenceAwareEngine(
            FakeTaskPort tasks,
            InMemoryAgentStageRunStore stages,
            InMemoryAgentStageArtifactStore artifacts,
            InMemoryTaskMaterialStore materials,
            InMemoryTaskRetryCheckpointStore checkpoints
    ) {
        InMemoryRetrievalRunStore retrievals = new InMemoryRetrievalRunStore();
        InMemoryAiReviewRunStore reviews = new InMemoryAiReviewRunStore();
        InMemoryTaskRetryFailureProvenanceStore provenanceStore = new InMemoryTaskRetryFailureProvenanceStore();
        provenanceStore.save(inferredAgentRoleProvenance(tasks.task, stages));
        TaskFailureRecoveryService recoveryService = new TaskFailureRecoveryService(
                tasks, stages, artifacts, retrievals, reviews, checkpoints,
                provenanceStore, new TaskRetryPointResolver(), new TaskFailureDiagnosticParser()
        );
        AtomicInteger ids = new AtomicInteger();
        InMemoryTaskRetryAttemptBindingStore bindings = new InMemoryTaskRetryAttemptBindingStore();
        TaskRetryEngine engine = new TaskRetryEngine(
                tasks, stages, retrievals, reviews, checkpoints, materials, recoveryService,
                new TaskRetryPointResolver(), new TaskRetryDispatcherPort() {
                    @Override
                    public void dispatch(String taskId, TaskRetryPoint retryPoint) {
                        throw new IllegalStateException(
                                "retry dispatch requires an exact durable checkpoint route: " + taskId);
                    }

                    @Override
                    public void dispatchCheckpoint(String checkpointId) {
                    }
                },
                () -> Integer.toString(ids.incrementAndGet()), () -> 1_000L + ids.get()
        );
        engine.setTaskRetryAttemptBindingStore(bindings);
        engine.setRequirementRetryDispatchTransactionPort(new InMemoryRequirementRetryDispatchTransactionAdapter(
                tasks, checkpoints, new InMemoryRequirementStageCommandStore(), bindings));
        return engine;
    }

    private AgentStageRun stage(String id, AgentRole role, int attempt, AgentStageStatus status) {
        return AgentStageRun.pending(id, "task-1", role, attempt,
                        "task-1:" + role + ":" + attempt, 10L)
                .withStatus(status, status.name().startsWith("FAILED") ? "TEST" : "",
                        status.name().startsWith("FAILED") ? "failed" : "", 100L + attempt);
    }

    private TaskRetryCommand guardedCommand(String commandId, String stage, long fence) {
        return new TaskRetryCommand(
                "coding-1", "", "", 300L, "", List.of(), "",
                commandId, stage, fence);
    }

    private RetrievalRun retrievalRun(String runId) {
        return new RetrievalRun(
                runId, "task-1", RetrievalConsumerType.REQUIREMENT_BASE, AgentRole.CODING_AGENT.name(), "", 1, "",
                "task-1:REQUIREMENT_BASE:1", RetrievalRunStatus.CREATED, List.of("kb-1"),
                "sha256:query", "redacted query", 0, 3, 18_000, 0, 0,
                EvidenceQualityDecision.SUFFICIENT, "", "", "", "", 0L, 0L, 100L, 100L
        );
    }

    private RdRequirementTask task(RdTaskStatus status, String resultJson) {
        return new RdRequirementTask(
                "task-1", "REQUIREMENT", "ADMIN", "source", "", "P1", status,
                "订单状态筛选", "project-1", "waimai", "外卖项目", "https://github.example/waimai",
                "owner", "repo", "main", "feature/status", "支持状态筛选", "[\"筛选正确\"]",
                "prompt", resultJson, "", "failed", 10L, 300L, false, 0L, 300L, 400L);
    }

    private static final class FakeTaskPort implements TaskRetryTaskPort {
        private RdRequirementTask task;

        private FakeTaskPort(RdRequirementTask task) {
            this.task = task;
        }

        @Override
        public RdRequirementTask getRequirementTask(String taskId) {
            return task;
        }

        @Override
        public RdRequirementTask markRecovering(String taskId, String reason) {
            task = task.withState(RdTaskStatus.RECOVERING, "", "", "", reason,
                    task.updateTimeEpochMillis() + 1L)
                    .withConcurrency(task.version() + 1L, task.fencingToken() + 1L);
            return task;
        }

        @Override
        public RdRequirementTask markRetryPreparationFailed(String taskId, String reason) {
            task = task.withState(RdTaskStatus.FAILED_RETRYABLE, "", task.executionResultJson(), "", reason,
                    task.updateTimeEpochMillis() + 1L)
                    .withConcurrency(task.version() + 1L, task.fencingToken() + 1L);
            return task;
        }
    }
}
