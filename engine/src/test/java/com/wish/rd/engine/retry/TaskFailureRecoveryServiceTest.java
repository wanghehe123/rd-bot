package com.wish.rd.engine.retry;

import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.requirement.job.RequirementStageFinalizationPort;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageFinalizationPort;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.review.impl.InMemoryAiReviewRunStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryAttemptBindingStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryCheckpointStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryFailureProvenanceStore;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskFailureRecoverySnapshot;
import com.wish.rd.engine.retry.model.TaskRetryAttemptBinding;
import com.wish.rd.engine.retry.model.TaskRetryAttemptKind;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskRetryFailureProvenance;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import com.wish.rd.rag.runtime.impl.CoordinatedRdTaskStatePersistence;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskFailureRecoveryServiceTest {

    @Test
    void returnsLatestFailedStageWithParsedDiagnosisAndBoundedRawArtifact() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        InMemoryAgentStageArtifactStore artifacts = new InMemoryAgentStageArtifactStore();
        AgentStageRun failed = AgentStageRun.pending(
                        "stage-review-2", "task-1", AgentRole.REQUIREMENT_REVIEWER, 2,
                        "task-1:REQUIREMENT_REVIEWER:2", 20L)
                .withResultArtifactId("result-review-2", 30L)
                .withProviderMetadata("long-cat", "[]", 30L)
                .withStatus(AgentStageStatus.FAILED_NEEDS_HUMAN,
                        "REQUIREMENT_REVIEW_NEEDS_HUMAN", "需要补充需求信息", 40L);
        stages.save(AgentStageRun.pending(
                "stage-review-1", "task-1", AgentRole.REQUIREMENT_REVIEWER, 1,
                "task-1:REQUIREMENT_REVIEWER:1", 10L));
        stages.save(failed);
        artifacts.save(new AgentStageArtifact(
                "result-review-2", "stage-review-2", "task-1", AgentRole.REQUIREMENT_REVIEWER,
                "RESULT_JSON", "rd-artifact://stage-review-2/result", "需求评审结果",
                """
                        {"decision":"NEED_INFO","missingInformation":["确认顾客测试账号"]}
                        """,
                "sha256:review", "{}", 40L
        ));

        TaskFailureRecoveryService service = new TaskFailureRecoveryService(
                new FakeTaskPort(failedTask()),
                stages,
                artifacts,
                new InMemoryRetrievalRunStore(),
                new InMemoryAiReviewRunStore(),
                new InMemoryTaskRetryCheckpointStore(),
                new TaskRetryPointResolver(),
                new TaskFailureDiagnosticParser()
        );

        TaskFailureRecoverySnapshot snapshot = service.snapshot("task-1");

        assertEquals("stage-review-2", snapshot.retryPoint().failedStageRunId());
        assertEquals(2, snapshot.failedAttemptNo());
        assertEquals("long-cat", snapshot.providerName());
        assertEquals("result-review-2", snapshot.rawResultArtifactId());
        assertEquals("sha256:review", snapshot.rawResultContentHash());
        assertTrue(snapshot.diagnostic().requiresSupplement());
        assertEquals("确认顾客测试账号", snapshot.diagnostic().issues().getFirst().detail());
    }

    @Test
    void resolvesOnlyTheProvenanceWhoseTaskSnapshotExactlyMatchesTheFailedTask() {
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        stages.save(AgentStageRun.pending(
                        "stage-coding-2", "task-1", AgentRole.CODING_AGENT, 2,
                        "task-1:CODING_AGENT:2", 20L)
                .withStatus(AgentStageStatus.FAILED_RETRYABLE, "EXECUTOR", "provider timeout", 40L));
        InMemoryTaskRetryFailureProvenanceStore provenance = new InMemoryTaskRetryFailureProvenanceStore();
        provenance.save(new TaskRetryFailureProvenance(
                "provenance-1", "task-1", "command-coding-2", 1,
                "ROLE_EXECUTION:CODING_AGENT", TaskFailurePhase.AGENT_ROLE,
                RdTaskStatus.FAILED_NEEDS_HUMAN, 17L, 29L,
                "stage-coding-2", "", "", "policy-1",
                "sha256:" + "b".repeat(64), "", "TECHNICAL_EXHAUSTED", 50L));
        TaskFailureRecoveryService service = service(stages, provenance, failedTask(17L, 29L));

        TaskFailureRecoverySnapshot snapshot = service.snapshot("task-1");

        assertEquals("command-coding-2", snapshot.retryPoint().failedStageCommandId());
        assertEquals(29L, snapshot.retryPoint().sourceFencingToken());
    }

    @Test
    void resolvesFinalizedBusinessRejectionAndMarkerlessPolicyExhaustionFromExactRows() {
        InMemoryTaskRetryFailureProvenanceStore businessStore = new InMemoryTaskRetryFailureProvenanceStore();
        businessStore.save(new TaskRetryFailureProvenance(
                "provenance-review", "task-1", "command-review", 1,
                "DETERMINISTIC_REVIEW", TaskFailurePhase.DETERMINISTIC_REVIEW,
                RdTaskStatus.REJECTED, 17L, 29L,
                "", "", "", "policy-1", "sha256:" + "b".repeat(64), "",
                "BUSINESS_REJECTED", 50L));
        InMemoryTaskRetryFailureProvenanceStore policyStore = new InMemoryTaskRetryFailureProvenanceStore();
        policyStore.save(new TaskRetryFailureProvenance(
                "provenance-policy", "task-1", "command-policy", 3,
                "POLICY_APPLY", TaskFailurePhase.POLICY,
                RdTaskStatus.DEAD_LETTERED, 18L, 30L,
                "", "", "", "policy-2", "sha256:" + "c".repeat(64), "",
                "POLICY_CONTROL_EXHAUSTED", 60L));

        TaskFailureRecoverySnapshot business = service(
                new InMemoryAgentStageRunStore(), businessStore,
                failedTask(RdTaskStatus.REJECTED, 17L, 29L)).snapshot("task-1");
        TaskFailureRecoverySnapshot policy = service(
                new InMemoryAgentStageRunStore(), policyStore,
                failedTask(RdTaskStatus.DEAD_LETTERED, 18L, 30L)).snapshot("task-1");

        assertEquals(TaskFailurePhase.DETERMINISTIC_REVIEW, business.retryPoint().failurePhase());
        assertEquals("command-review", business.retryPoint().failedStageCommandId());
        assertEquals(TaskFailurePhase.POLICY, policy.retryPoint().failurePhase());
        assertEquals("command-policy", policy.retryPoint().failedStageCommandId());
    }

    @Test
    void resolvesHostVerifyProvenanceToCodingWithoutReadingErrorText() {
        InMemoryTaskRetryFailureProvenanceStore provenance = new InMemoryTaskRetryFailureProvenanceStore();
        provenance.save(new TaskRetryFailureProvenance(
                "provenance-verify", "task-1", "command-verify", 1,
                "HOST_VERIFY", TaskFailurePhase.HOST_VERIFY,
                RdTaskStatus.FAILED_NEEDS_HUMAN, 17L, 29L,
                "", "", "", "policy-1", "sha256:" + "b".repeat(64), "",
                "PRODUCT_DEFECT", 50L, "verify-9"));
        RdRequirementTask failed = new RdRequirementTask(
                "task-1", "REQUIREMENT", "ADMIN", "source", "", "P1", RdTaskStatus.FAILED_NEEDS_HUMAN,
                "订单状态筛选", "project-1", "waimai", "外卖项目", "https://github.example/waimai",
                "owner", "repo", "main", "feature/status", "支持状态筛选", "[\"筛选正确\"]",
                "prompt", "{\"errorMessage\":\"QA publication failed\"}",
                "", "QA publication failed", 10L, 300L, false, 0L, 17L, 29L);

        TaskFailureRecoverySnapshot snapshot = service(new InMemoryAgentStageRunStore(), provenance, failed)
                .snapshot("task-1");

        assertEquals(TaskFailurePhase.HOST_VERIFY, snapshot.retryPoint().failurePhase());
        assertEquals(AgentRole.CODING_AGENT, snapshot.retryPoint().retryFromRole());
        assertEquals("command-verify", snapshot.retryPoint().failedStageCommandId());
        assertEquals("HOST_VERIFY", snapshot.retryPoint().failedStage());
    }

    @Test
    void rejectsWhenNoExactDurableProvenanceMatchesTheCurrentFailedSnapshot() {
        InMemoryTaskRetryFailureProvenanceStore provenance = new InMemoryTaskRetryFailureProvenanceStore();
        provenance.save(new TaskRetryFailureProvenance(
                "provenance-old", "task-1", "command-old", 1,
                "ROLE_EXECUTION:CODING_AGENT", TaskFailurePhase.AGENT_ROLE,
                RdTaskStatus.FAILED_NEEDS_HUMAN, 16L, 28L,
                "stage-old", "", "", "", "", "", "BUSINESS", 50L));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service(new InMemoryAgentStageRunStore(), provenance, failedTask(17L, 29L))
                        .snapshot("task-1"));

        assertEquals("RETRY_POINT_AMBIGUOUS: task-1", failure.getMessage());
    }

    @Test
    void resolvesTheExactRoleRetryPointAfterItsCheckpointBoundCommandWasTechnicallyExhausted() {
        long now = 1_784_910_000_000L;
        String taskId = "task-1";
        String checkpointId = "101";
        long sourceVersion = 12L;
        long sourceFence = 13L;
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        taskStore.saveRequirementTask(new RdRequirementTask(
                taskId, "REQUIREMENT", "ADMIN", "source", "", "P1", RdTaskStatus.RECOVERING,
                "订单状态筛选", "project-1", "waimai", "外卖项目", "https://github.example/waimai",
                "owner", "repo", "main", "feature/status", "支持状态筛选", "[\"筛选正确\"]",
                "prompt", "{}", "", "recovering", 10L, now, false, 0L, sourceVersion, sourceFence));
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        InMemoryTaskRetryAttemptBindingStore bindings = new InMemoryTaskRetryAttemptBindingStore();
        InMemoryTaskRetryFailureProvenanceStore provenanceStore = new InMemoryTaskRetryFailureProvenanceStore();
        InMemoryAgentStageRunStore stages = new InMemoryAgentStageRunStore();
        stages.save(AgentStageRun.pending(
                        "coding-1", taskId, AgentRole.CODING_AGENT, 1,
                        taskId + ":CODING_AGENT:1", now)
                .withStatus(AgentStageStatus.FAILED_RETRYABLE, "PROVIDER", "missing key", now));
        stages.save(AgentStageRun.pending(
                "coding-2", taskId, AgentRole.CODING_AGENT, 2,
                taskId + ":CODING_AGENT:2", now));
        TaskRetryCheckpoint checkpoint = new TaskRetryCheckpoint(
                checkpointId, taskId, TaskFailurePhase.AGENT_ROLE, AgentRole.CODING_AGENT,
                "coding-1", "", "", 1, taskId + ":11:AGENT_ROLE:CODING_AGENT",
                RdTaskStatus.FAILED_NEEDS_HUMAN, 11L, 12L,
                "command-old", "ROLE_EXECUTION:CODING_AGENT", "policy-1", "",
                "sha256:" + "a".repeat(64), "", "", 0L, 0L, "", 101L,
                "", java.util.List.of(), TaskRetryCheckpointStatus.CREATED, "missing key", "", now, now);
        checkpoints.createOrGet(checkpoint);
        checkpoints.dispatch(checkpointId, sourceVersion, sourceFence, "command-retry", now);
        TaskRetryAttemptBinding binding = new TaskRetryAttemptBinding(
                "binding-coding", checkpointId, TaskRetryAttemptKind.AGENT_STAGE,
                AgentRole.CODING_AGENT, "coding-2", "", 2, 0);
        bindings.save(binding);
        RequirementStageCommand pending = RequirementStageCommand.pending(
                "command-retry", taskId, sourceVersion, sourceFence,
                AgentRole.CODING_AGENT.name(), "ROLE_EXECUTION:CODING_AGENT",
                0, 1, now + 60_000L, ScheduleResourceClass.GENERIC,
                java.util.Set.of(ScheduleResourceClass.GENERIC), "project-1", "", "P1", "policy-1",
                checkpointId, 101L, binding.bindingId(), now);
        commands.enqueue(pending);
        RequirementStageCommand claimed = commands.claim(pending.commandId(), "worker-1", now, 30_000L)
                .orElseThrow();
        InMemoryRequirementStageFinalizationPort finalizer = new InMemoryRequirementStageFinalizationPort(
                commands, new InMemoryRequirementDeliveryJobStore(), taskStore,
                new CoordinatedRdTaskStatePersistence(taskStore, events),
                new SnowflakeIdGenerator(1, 1, () -> now), null, null,
                provenanceStore, checkpoints, bindings, stages);
        var draft = new TaskRetryExhaustionProvenanceFactory(checkpoints, bindings).draftFor(
                claimed, "provenance-exhaust", "TECHNICAL_EXHAUSTED",
                TaskRetryExhaustionProvenanceFactory.ExecutionContext.empty());

        finalizer.exhaustCommand(new RequirementStageFinalizationPort.ExhaustionCommand(
                claimed, "worker-1", RdTaskStatus.RECOVERING, sourceVersion, sourceFence,
                RdTaskStatus.FAILED_RETRYABLE, TaskRetryCheckpointStatus.FAILED_RETRYABLE, draft,
                "role command task is not executing: " + claimed.commandId(), "{}", now + 3L));

        TaskFailureRecoverySnapshot snapshot = service(
                stages, provenanceStore, taskStore.findRequirementTask(taskId).orElseThrow())
                .snapshot(taskId);

        assertEquals(TaskFailurePhase.AGENT_ROLE, snapshot.retryPoint().failurePhase());
        assertEquals(AgentRole.CODING_AGENT, snapshot.retryPoint().retryFromRole());
        assertEquals("ROLE_EXECUTION:CODING_AGENT", snapshot.retryPoint().failedStage());
        assertEquals("command-retry", snapshot.retryPoint().failedStageCommandId());
    }

    private static RdRequirementTask failedTask() {
        return failedTask(0L, 0L);
    }

    private static RdRequirementTask failedTask(long version, long fence) {
        return failedTask(RdTaskStatus.FAILED_NEEDS_HUMAN, version, fence);
    }

    private static RdRequirementTask failedTask(RdTaskStatus status, long version, long fence) {
        return new RdRequirementTask(
                "task-1", "REQUIREMENT", "ADMIN", "source", "", "P1", status,
                "订单状态筛选", "project-1", "waimai", "外卖项目", "https://github.example/waimai",
                "owner", "repo", "main", "feature/status", "支持状态筛选", "[\"筛选正确\"]",
                "prompt", "{}", "", "需要补充需求信息", 10L, 300L, false, 0L, version, fence
        );
    }

    private static TaskFailureRecoveryService service(
            InMemoryAgentStageRunStore stages,
            InMemoryTaskRetryFailureProvenanceStore provenance,
            RdRequirementTask task
    ) {
        return new TaskFailureRecoveryService(
                new FakeTaskPort(task), stages, new InMemoryAgentStageArtifactStore(),
                new InMemoryRetrievalRunStore(), new InMemoryAiReviewRunStore(),
                new InMemoryTaskRetryCheckpointStore(), provenance,
                new TaskRetryPointResolver(), new TaskFailureDiagnosticParser());
    }

    private static final class FakeTaskPort implements TaskRetryTaskPort {
        private final RdRequirementTask task;

        private FakeTaskPort(RdRequirementTask task) {
            this.task = task;
        }

        @Override
        public RdRequirementTask getRequirementTask(String taskId) {
            return task;
        }

        @Override
        public RdRequirementTask markRecovering(String taskId, String reason) {
            throw new UnsupportedOperationException("not used by recovery snapshot");
        }

        @Override
        public RdRequirementTask markRetryPreparationFailed(String taskId, String reason) {
            throw new UnsupportedOperationException("not used by recovery snapshot");
        }
    }
}
