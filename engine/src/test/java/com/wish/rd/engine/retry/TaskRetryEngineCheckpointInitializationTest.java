package com.wish.rd.engine.retry;

import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageCommandStore;
import com.wish.rd.engine.requirement.review.impl.InMemoryAiReviewRunStore;
import com.wish.rd.engine.retry.impl.InMemoryRequirementRetryDispatchTransactionAdapter;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryAttemptBindingStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryCheckpointStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryFailureProvenanceStore;
import com.wish.rd.engine.retry.model.InitializeRequirementRetryCommand;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryAttemptBinding;
import com.wish.rd.engine.retry.model.TaskRetryAttemptKind;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskRetryFailureProvenance;
import com.wish.rd.engine.retry.model.TaskRetryPoint;
import com.wish.rd.engine.retry.model.TaskRetryRoute;
import com.wish.rd.engine.scheduling.model.RequirementDeliverySchedulingPolicy;
import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import com.wish.rd.rag.runtime.impl.InMemoryTaskMaterialStore;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRetryEngineCheckpointInitializationTest {

    @Test
    void initializesAndWakesTheExactCheckpointCommandWithoutLegacyTaskWideDispatch() {
        FakeTaskPort tasks = new FakeTaskPort(task());
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        InMemoryRetrievalRunStore retrievals = new InMemoryRetrievalRunStore();
        InMemoryAiReviewRunStore reviews = new InMemoryAiReviewRunStore();
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        InMemoryTaskRetryFailureProvenanceStore provenance = new InMemoryTaskRetryFailureProvenanceStore();
        provenance.save(new TaskRetryFailureProvenance(
                "provenance-1", "task-1", "17", 1, "MATERIAL_COLLECTING", TaskFailurePhase.MATERIAL,
                RdTaskStatus.FAILED_NEEDS_HUMAN, 12L, 400L, "", "", "", "", "", "", "TECHNICAL", 1L));
        TaskFailureRecoveryService recovery = new TaskFailureRecoveryService(
                tasks, stages, new InMemoryAgentStageArtifactStore(), retrievals, reviews, checkpoints,
                provenance, new TaskRetryPointResolver(), new TaskFailureDiagnosticParser());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        AtomicInteger ids = new AtomicInteger(100);
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        TaskRetryEngine engine = new TaskRetryEngine(
                tasks, stages, retrievals, reviews, checkpoints, new InMemoryTaskMaterialStore(), recovery,
                new TaskRetryPointResolver(), dispatcher, () -> Integer.toString(ids.incrementAndGet()), () -> 1_000L);
        engine.setRequirementRetryDispatchTransactionPort(new InMemoryRequirementRetryDispatchTransactionAdapter(
                tasks, checkpoints, commands, new InMemoryTaskRetryAttemptBindingStore()));

        var checkpoint = engine.retry("task-1", "USER");

        // W4：初始化期间会为 checkpoint/attempt/binding/command 生成多个 ID，禁止断言全局序号；
        // 只断言身份关联——dispatch command 必须存在且绑定到本 checkpoint，唤醒走 checkpoint 通道。
        assertTrue(commands.findById(checkpoint.dispatchCommandId()).isPresent(),
                "checkpoint dispatch command must be persisted");
        commands.findById(checkpoint.dispatchCommandId()).ifPresent(command ->
                assertEquals(checkpoint.checkpointId(), command.retryCheckpointId(),
                        "dispatch command must reference its retry checkpoint"));
        assertEquals(checkpoint.checkpointId(), dispatcher.dispatchedCheckpointId);
        assertEquals("", dispatcher.legacyTaskId);
    }

    @Test
    void checkpointBoundRetryCommandUsesSchedulingCommandDeadlineNotLeaseWindow() {
        FakeTaskPort tasks = new FakeTaskPort(task());
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        InMemoryRetrievalRunStore retrievals = new InMemoryRetrievalRunStore();
        InMemoryAiReviewRunStore reviews = new InMemoryAiReviewRunStore();
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        InMemoryTaskRetryFailureProvenanceStore provenance = new InMemoryTaskRetryFailureProvenanceStore();
        provenance.save(new TaskRetryFailureProvenance(
                "provenance-1", "task-1", "17", 1, "MATERIAL_COLLECTING", TaskFailurePhase.MATERIAL,
                RdTaskStatus.FAILED_NEEDS_HUMAN, 12L, 400L, "", "", "", "", "", "", "TECHNICAL", 1L));
        TaskFailureRecoveryService recovery = new TaskFailureRecoveryService(
                tasks, stages, new InMemoryAgentStageArtifactStore(), retrievals, reviews, checkpoints,
                provenance, new TaskRetryPointResolver(), new TaskFailureDiagnosticParser());
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        AtomicInteger ids = new AtomicInteger(100);
        TaskRetryEngine engine = new TaskRetryEngine(
                tasks, stages, retrievals, reviews, checkpoints, new InMemoryTaskMaterialStore(), recovery,
                new TaskRetryPointResolver(), new RecordingDispatcher(),
                () -> Integer.toString(ids.incrementAndGet()), () -> 1_000L);
        engine.setRequirementRetryDispatchTransactionPort(new InMemoryRequirementRetryDispatchTransactionAdapter(
                tasks, checkpoints, commands, new InMemoryTaskRetryAttemptBindingStore()));

        var checkpoint = engine.retry("task-1", "USER");

        long expectedDeadline = 1_000L + RequirementDeliverySchedulingPolicy.defaults().commandDeadlineMillis();
        assertEquals(expectedDeadline, commands.findById(checkpoint.dispatchCommandId()).orElseThrow()
                .deadlineEpochMillis());
    }

    @Test
    void checkpointBoundRetryCommandHonorsConfiguredCommandDeadlineMillis() {
        FakeTaskPort tasks = new FakeTaskPort(task());
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        InMemoryRetrievalRunStore retrievals = new InMemoryRetrievalRunStore();
        InMemoryAiReviewRunStore reviews = new InMemoryAiReviewRunStore();
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        InMemoryTaskRetryFailureProvenanceStore provenance = new InMemoryTaskRetryFailureProvenanceStore();
        provenance.save(new TaskRetryFailureProvenance(
                "provenance-1", "task-1", "17", 1, "MATERIAL_COLLECTING", TaskFailurePhase.MATERIAL,
                RdTaskStatus.FAILED_NEEDS_HUMAN, 12L, 400L, "", "", "", "", "", "", "TECHNICAL", 1L));
        TaskFailureRecoveryService recovery = new TaskFailureRecoveryService(
                tasks, stages, new InMemoryAgentStageArtifactStore(), retrievals, reviews, checkpoints,
                provenance, new TaskRetryPointResolver(), new TaskFailureDiagnosticParser());
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        AtomicInteger ids = new AtomicInteger(100);
        TaskRetryEngine engine = new TaskRetryEngine(
                tasks, stages, retrievals, reviews, checkpoints, new InMemoryTaskMaterialStore(), recovery,
                new TaskRetryPointResolver(), new RecordingDispatcher(),
                () -> Integer.toString(ids.incrementAndGet()), () -> 1_000L);
        engine.setCommandDeadlineMillis(120_000L);
        engine.setRequirementRetryDispatchTransactionPort(new InMemoryRequirementRetryDispatchTransactionAdapter(
                tasks, checkpoints, commands, new InMemoryTaskRetryAttemptBindingStore()));

        var checkpoint = engine.retry("task-1", "USER");

        assertEquals(121_000L, commands.findById(checkpoint.dispatchCommandId()).orElseThrow()
                .deadlineEpochMillis());
    }

    @Test
    void roleRetryFirstCommandCarriesItsSourceAppliedPolicyAuthorization() {
        FakeTaskPort tasks = new FakeTaskPort(task());
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        InMemoryRequirementRetryDispatchTransactionAdapter adapter =
                new InMemoryRequirementRetryDispatchTransactionAdapter(
                        tasks, checkpoints, commands, new InMemoryTaskRetryAttemptBindingStore());
        TaskRetryCheckpoint checkpoint = new TaskRetryCheckpoint(
                "101", "task-1", TaskFailurePhase.AGENT_ROLE, AgentRole.CODING_AGENT,
                "coding-1", "", "", 1, "task-1:12:AGENT_ROLE:CODING_AGENT",
                RdTaskStatus.FAILED_NEEDS_HUMAN, 12L, 400L,
                "command-old", "ROLE_EXECUTION:CODING_AGENT", "policy-1", "",
                "sha256:" + "a".repeat(64), "", "", 0L, 0L, "", 101L,
                "", java.util.List.of(), TaskRetryCheckpointStatus.CREATED, "failed", "", 1_000L, 1_000L);
        TaskRetryRoute route = new TaskRetryRoute(
                RdTaskStatus.RECOVERING, AgentRole.CODING_AGENT.name(), "ROLE_EXECUTION:CODING_AGENT",
                TaskRetryAttemptKind.AGENT_STAGE, null, java.util.List.of(AgentRole.CODING_AGENT));
        TaskRetryAttemptBinding binding = new TaskRetryAttemptBinding(
                "binding-1", checkpoint.checkpointId(), TaskRetryAttemptKind.AGENT_STAGE,
                AgentRole.CODING_AGENT, "coding-2", "", 2, 0);

        var result = adapter.initialize(checkpoint, winner -> new InitializeRequirementRetryCommand(
                winner, route, "command-2", 3, 2_000L, "project-1", "", "P1", binding.bindingId(),
                java.util.List.of(binding), 1_000L));

        assertEquals("policy-1", result.firstCommand().policyRunId());
    }

    private static RdRequirementTask task() {
        return new RdRequirementTask("task-1", "REQUIREMENT", "ADMIN", "source", "", "P1",
                RdTaskStatus.FAILED_NEEDS_HUMAN, "title", "project-1", "key", "project",
                "repo", "owner", "repo", "main", "feature", "expected", "[]", "", "{}", "",
                "failed", 1L, 1L, false, 0L, 12L, 400L);
    }

    private static final class RecordingDispatcher implements TaskRetryDispatcherPort {
        private String legacyTaskId = "";
        private String dispatchedCheckpointId = "";

        @Override
        public void dispatch(String taskId, TaskRetryPoint retryPoint) {
            legacyTaskId = taskId;
        }

        @Override
        public void dispatchCheckpoint(String checkpointId) {
            dispatchedCheckpointId = checkpointId;
        }
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
            task = task.withState(RdTaskStatus.RECOVERING, "", "", "", reason, 2L)
                    .withConcurrency(task.version() + 1L, task.fencingToken() + 1L);
            return task;
        }

        @Override
        public RdRequirementTask markRetryPreparationFailed(String taskId, String reason) {
            task = task.withState(RdTaskStatus.FAILED_RETRYABLE, "", "", "", reason, 3L)
                    .withConcurrency(task.version() + 1L, task.fencingToken() + 1L);
            return task;
        }
    }
}
