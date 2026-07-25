package com.wish.rd.engine.requirement;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import com.wish.rd.rag.retrieval.run.model.EvidenceQualityDecision;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementContextRetrievalRecorderTest {

    @Test
    void createsBaseAndRoleRetrievalRunsFromTheSameRequirementEvidence() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        RequirementContextRetrievalRecorder recorder = new RequirementContextRetrievalRecorder(
                new RetrievalRunLifecycle(store, () -> "run-" + ids.incrementAndGet(), () -> 100L)
        );
        RdRequirementTask task = RagStreamTaskRegistry.inMemory().createRequirementTask(new CreateRequirementTaskCommand(
                "支付导出", "P2", "https://github.com/example/payment.git", "example", "payment", "main",
                "完成支付导出", List.of("导出字段正确"), false
        ));
        List<TaskMaterial> materials = List.of(new TaskMaterial(
                "material-1", task.taskId(), TaskMaterialType.REQUIREMENT_DOC, TaskMaterialSourceType.MANUAL_TEXT,
                "需求说明", "manual://1", "text/plain", "sha256:1", "支付导出需要包含订单金额", "", "", "", "", 100L, 100L
        ));

        recorder.record(task, materials);

        assertEquals(1, store.listByTask(task.taskId()).stream()
                .filter(run -> run.consumerType() == RetrievalConsumerType.REQUIREMENT_BASE).count());
        assertEquals(AgentRole.requirementDeliveryOrder().size(), store.listByTask(task.taskId()).stream()
                .filter(run -> run.consumerType() == RetrievalConsumerType.AGENT_ROLE).count());
    }

    @Test
    void reusesNonTerminalRunsForTheSameConsumerAndRoleOnResubmit() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        RetrievalRunLifecycle lifecycle = new RetrievalRunLifecycle(store, () -> "run-" + ids.incrementAndGet(), () -> 100L);
        RequirementContextRetrievalRecorder recorder = new RequirementContextRetrievalRecorder(lifecycle);
        RdRequirementTask task = requirementTask();
        String query = (task.title() + " " + task.expectedResult() + " " + task.acceptanceCriteriaJson()).strip();
        lifecycle.start(task.taskId(), RetrievalConsumerType.REQUIREMENT_BASE, "", "", query, List.of());
        for (AgentRole role : AgentRole.requirementDeliveryOrder()) {
            lifecycle.start(task.taskId(), RetrievalConsumerType.AGENT_ROLE, role.name(), "",
                    role.name() + " " + query, List.of());
        }
        int before = store.listByTask(task.taskId()).size();
        assertTrue(store.listByTask(task.taskId()).stream().noneMatch(run -> run.status().isTerminal()));

        recorder.record(task, List.of(material(task.taskId())));

        assertEquals(before, store.listByTask(task.taskId()).size());
        assertTrue(store.listByTask(task.taskId()).stream().allMatch(run -> run.status().isTerminal()));
    }

    @Test
    void retriesTerminalRunsInsteadOfCollidingOnAttemptOneIdempotencyKey() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        RequirementContextRetrievalRecorder recorder = new RequirementContextRetrievalRecorder(
                new RetrievalRunLifecycle(store, () -> "run-" + ids.incrementAndGet(), () -> 100L)
        );
        RdRequirementTask task = requirementTask();
        List<TaskMaterial> materials = List.of(material(task.taskId()));

        recorder.record(task, materials);
        List<RetrievalRun> firstPass = store.listByTask(task.taskId());
        assertTrue(firstPass.stream().allMatch(run -> run.status().isTerminal()));

        recorder.record(task, materials);

        List<RetrievalRun> baseRuns = store.listByTask(task.taskId()).stream()
                .filter(run -> run.consumerType() == RetrievalConsumerType.REQUIREMENT_BASE)
                .toList();
        assertEquals(2, baseRuns.size());
        assertEquals(1, baseRuns.get(0).attemptNo());
        assertEquals(2, baseRuns.get(1).attemptNo());
        assertNotEquals(baseRuns.get(0).idempotencyKey(), baseRuns.get(1).idempotencyKey());
        assertEquals(baseRuns.get(0).runId(), baseRuns.get(1).parentRunId());
    }

    @Test
    void missingRequirementMaterialStillBlocksOnlyTheRolesThatNeedIt() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        RequirementContextRetrievalRecorder recorder = new RequirementContextRetrievalRecorder(
                new RetrievalRunLifecycle(store, () -> "run-" + ids.incrementAndGet(), () -> 100L)
        );
        RdRequirementTask task = requirementTask();

        recorder.record(task, List.of());

        RetrievalRun base = run(store, task.taskId(), RetrievalConsumerType.REQUIREMENT_BASE, "");
        RetrievalRun reviewer = run(store, task.taskId(), RetrievalConsumerType.AGENT_ROLE,
                AgentRole.REQUIREMENT_REVIEWER.name());
        assertEquals(RetrievalRunStatus.WAITING_INPUT, base.status());
        assertEquals(RetrievalRunStatus.WAITING_INPUT, reviewer.status());
        for (AgentRole role : List.of(AgentRole.SOLUTION_ARCHITECT, AgentRole.CODING_AGENT, AgentRole.QA_AGENT)) {
            RetrievalRun run = run(store, task.taskId(), RetrievalConsumerType.AGENT_ROLE, role.name());
            assertEquals(RetrievalRunStatus.SUCCEEDED_DEGRADED, run.status());
            assertEquals(EvidenceQualityDecision.DEGRADED_ACCEPTABLE, run.qualityDecision());
        }
    }

    @Test
    void requirementTextAloneAllowsCodingAndQaToDiscoverTheRepositoryWithinBounds() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        RequirementContextRetrievalRecorder recorder = new RequirementContextRetrievalRecorder(
                new RetrievalRunLifecycle(store, () -> "run-" + ids.incrementAndGet(), () -> 100L)
        );
        RdRequirementTask task = requirementTask();

        recorder.record(task, List.of(material(task.taskId())));

        for (AgentRole role : List.of(AgentRole.CODING_AGENT, AgentRole.QA_AGENT)) {
            RetrievalRun run = store.listByTask(task.taskId()).stream()
                    .filter(candidate -> candidate.role().equals(role.name()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(RetrievalRunStatus.SUCCEEDED_DEGRADED, run.status(),
                    role + " should receive a bounded repository discovery fallback");
            assertEquals(EvidenceQualityDecision.DEGRADED_ACCEPTABLE, run.qualityDecision());
            assertTrue(run.stopReason().contains("受限仓库发现"), run.stopReason());
        }
    }

    @Test
    void exposesRequirementMaterialContentAndPackagingProcessForEveryRun() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        RequirementContextRetrievalRecorder recorder = new RequirementContextRetrievalRecorder(
                new RetrievalRunLifecycle(store, () -> "run-" + ids.incrementAndGet(), () -> 100L)
        );
        RdRequirementTask task = requirementTask();

        recorder.record(task, List.of(material(task.taskId())));

        for (RetrievalRun run : store.listByTask(task.taskId())) {
            var artifacts = store.listArtifacts(run.runId());
            assertTrue(artifacts.stream().anyMatch(artifact -> artifact.artifactType().equals("RETRIEVAL_PLAN")),
                    "base and role retrieval runs must expose their plan");
            assertTrue(artifacts.stream().anyMatch(artifact -> artifact.artifactType().equals("MATERIAL_EVIDENCE")
                            && artifact.contentPreview().contains("支付导出需要包含订单金额")),
                    "operators must see the redacted material content selected for the role");
            assertTrue(artifacts.stream().anyMatch(artifact -> artifact.artifactType().equals("RETRIEVAL_OUTCOME")),
                    "the packaging outcome must be visible for every role run");
        }
    }

    @Test
    void roleRetryOnlyCreatesRetrievalAttemptsFromTheFailedRoleForward() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        RequirementContextRetrievalRecorder recorder = new RequirementContextRetrievalRecorder(
                new RetrievalRunLifecycle(store, () -> "run-" + ids.incrementAndGet(), () -> 100L)
        );
        RdRequirementTask task = requirementTask();
        List<TaskMaterial> materials = List.of(material(task.taskId()));
        recorder.record(task, materials);

        recorder.recordFromRole(task, materials, AgentRole.SOLUTION_ARCHITECT);

        assertEquals(1, attempts(store, task.taskId(), RetrievalConsumerType.REQUIREMENT_BASE, ""));
        assertEquals(1, attempts(store, task.taskId(), RetrievalConsumerType.AGENT_ROLE,
                AgentRole.REQUIREMENT_REVIEWER.name()));
        assertEquals(2, attempts(store, task.taskId(), RetrievalConsumerType.AGENT_ROLE,
                AgentRole.SOLUTION_ARCHITECT.name()));
        assertEquals(2, attempts(store, task.taskId(), RetrievalConsumerType.AGENT_ROLE,
                AgentRole.CODING_AGENT.name()));
        assertEquals(2, attempts(store, task.taskId(), RetrievalConsumerType.AGENT_ROLE,
                AgentRole.QA_AGENT.name()));
    }

    @Test
    void ragRetryExecutesThePrecreatedChildWithoutCreatingAnotherAttempt() {
        InMemoryRetrievalRunStore store = new InMemoryRetrievalRunStore();
        AtomicInteger ids = new AtomicInteger();
        RetrievalRunLifecycle lifecycle = new RetrievalRunLifecycle(
                store, () -> "run-" + ids.incrementAndGet(), () -> 100L);
        RequirementContextRetrievalRecorder recorder = new RequirementContextRetrievalRecorder(lifecycle);
        RdRequirementTask task = requirementTask();
        List<TaskMaterial> materials = List.of(material(task.taskId()));
        recorder.record(task, materials);
        RetrievalRun failed = store.listByTask(task.taskId()).stream()
                .filter(run -> run.role().equals(AgentRole.QA_AGENT.name()))
                .findFirst().orElseThrow();
        RetrievalRun retry = store.retry(failed.runId(), "precreated-child", 110L);

        recorder.recordOnly(task, materials, RetrievalConsumerType.AGENT_ROLE, AgentRole.QA_AGENT);

        assertEquals(2, attempts(store, task.taskId(), RetrievalConsumerType.AGENT_ROLE,
                AgentRole.QA_AGENT.name()));
        assertEquals(RetrievalRunStatus.SUCCEEDED_DEGRADED, store.find(retry.runId()).orElseThrow().status(),
                "a pre-created retry attempt must retain the QA repository-discovery fallback");
    }

    private static long attempts(
            InMemoryRetrievalRunStore store,
            String taskId,
            RetrievalConsumerType consumerType,
            String role
    ) {
        return store.listByTask(taskId).stream()
                .filter(run -> run.consumerType() == consumerType)
                .filter(run -> role.equals(run.role()))
                .count();
    }

    private static RetrievalRun run(
            InMemoryRetrievalRunStore store,
            String taskId,
            RetrievalConsumerType consumerType,
            String role
    ) {
        return store.listByTask(taskId).stream()
                .filter(candidate -> candidate.consumerType() == consumerType)
                .filter(candidate -> role.equals(candidate.role()))
                .findFirst()
                .orElseThrow();
    }

    private static RdRequirementTask requirementTask() {
        return RagStreamTaskRegistry.inMemory().createRequirementTask(new CreateRequirementTaskCommand(
                "支付导出", "P2", "https://github.com/example/payment.git", "example", "payment", "main",
                "完成支付导出", List.of("导出字段正确"), false
        ));
    }

    private static TaskMaterial material(String taskId) {
        return new TaskMaterial(
                "material-1", taskId, TaskMaterialType.REQUIREMENT_DOC, TaskMaterialSourceType.MANUAL_TEXT,
                "需求说明", "manual://1", "text/plain", "sha256:1", "支付导出需要包含订单金额", "", "", "", "", 100L, 100L
        );
    }
}
