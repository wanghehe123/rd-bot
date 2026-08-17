package com.wish.rd.engine.retry;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryAttemptBindingStore;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryAttemptBinding;
import com.wish.rd.engine.retry.model.TaskRetryAttemptKind;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryPoint;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRetryRoutePlannerTest {

    private final TaskRetryRoutePlanner planner = new TaskRetryRoutePlanner();

    @Test
    void plansEveryFrozenRecoveryRouteFromExactFailureProvenance() {
        assertRoute(point(TaskFailurePhase.MATERIAL, null, "MATERIAL_COLLECTING", "", ""),
                "REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING", null,
                AgentRole.requirementDeliveryOrder());
        assertRoute(point(TaskFailurePhase.MATERIAL, null, "MATERIAL_READY", "", ""),
                "REQUIREMENT_DELIVERY", "MATERIAL_READY", null,
                AgentRole.requirementDeliveryOrder());
        assertRoute(point(TaskFailurePhase.CONTEXT, null, "CONTEXT_BUILDING", "", ""),
                "REQUIREMENT_DELIVERY", "CONTEXT_BUILDING", null,
                AgentRole.requirementDeliveryOrder());
        assertRoute(point(TaskFailurePhase.CONTEXT, null, "CONTEXT_READY", "", ""),
                "REQUIREMENT_DELIVERY", "CONTEXT_READY", null,
                AgentRole.requirementDeliveryOrder());
        assertRoute(point(TaskFailurePhase.PLAN, null, "PLAN_GENERATING", "", ""),
                "REQUIREMENT_DELIVERY", "PLAN_GENERATING", null,
                AgentRole.requirementDeliveryOrder());
        assertRoute(point(TaskFailurePhase.PLAN, null, "PLAN_GENERATED", "", ""),
                "REQUIREMENT_DELIVERY", "PLAN_GENERATED", null,
                AgentRole.requirementDeliveryOrder());
        assertRoute(point(TaskFailurePhase.POLICY, null, "POLICY_EVALUATE", "policy-1", ""),
                "REQUIREMENT_DELIVERY", "POLICY_EVALUATE", null,
                AgentRole.requirementDeliveryOrder());
        assertRoute(point(TaskFailurePhase.POLICY, null, "POLICY_APPLY", "policy-1", ""),
                "REQUIREMENT_DELIVERY", "POLICY_EVALUATE", null,
                AgentRole.requirementDeliveryOrder());
        assertRoute(point(TaskFailurePhase.POLICY, null, "APPROVAL_RESUME", "policy-1", ""),
                "REQUIREMENT_DELIVERY", "POLICY_EVALUATE", null,
                AgentRole.requirementDeliveryOrder());
        TaskRetryPoint rag = point(TaskFailurePhase.RAG, AgentRole.CODING_AGENT,
                "ROLE_EXECUTION:CODING_AGENT", "policy-1", "");
        assertRoute(rag,
                "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", TaskRetryAttemptKind.AGENT_STAGE,
                List.of(AgentRole.CODING_AGENT, AgentRole.QA_AGENT));
        assertEquals(TaskRetryAttemptKind.RETRIEVAL, planner.plan(rag).associatedAttemptKind());
        assertRoute(point(TaskFailurePhase.AGENT_ROLE, AgentRole.CODING_AGENT,
                        "ROLE_EXECUTION:CODING_AGENT", "policy-1", ""),
                "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", TaskRetryAttemptKind.AGENT_STAGE,
                List.of(AgentRole.CODING_AGENT, AgentRole.QA_AGENT));
        assertRoute(point(TaskFailurePhase.DETERMINISTIC_REVIEW, null, "DETERMINISTIC_REVIEW", "policy-1", ""),
                "REQUIREMENT_DELIVERY", "DETERMINISTIC_REVIEW", null, List.of());
        assertRoute(point(TaskFailurePhase.AI_REVIEW, null, "AI_REVIEW", "policy-1", ""),
                "REQUIREMENT_DELIVERY", "AI_REVIEW", TaskRetryAttemptKind.AI_REVIEW, List.of());
        assertRoute(point(TaskFailurePhase.AI_REVIEW, AgentRole.CODING_AGENT,
                        "AI_REVIEW", "policy-1", ""),
                "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", TaskRetryAttemptKind.AGENT_STAGE,
                List.of(AgentRole.CODING_AGENT, AgentRole.QA_AGENT));
        assertRoute(point(TaskFailurePhase.PR_PUBLICATION, null, "PUBLICATION:operation-1", "policy-1", "operation-1"),
                "REQUIREMENT_DELIVERY", "PUBLICATION:operation-1", null, List.of());
        assertRoute(point(TaskFailurePhase.HOST_VERIFY, AgentRole.CODING_AGENT, "HOST_VERIFY", "policy-1", ""),
                "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", TaskRetryAttemptKind.AGENT_STAGE,
                List.of(AgentRole.CODING_AGENT, AgentRole.QA_AGENT));
    }

    @Test
    void mapsBarePublicationStageToPrPublicationPhase() {
        assertEquals(TaskFailurePhase.PR_PUBLICATION,
                TaskRetryRoutePlanner.phaseForStage("PUBLICATION", null));
    }

    @Test
    void rejectsMissingExactRouteIdentityBeforeAnythingCanBePersisted() {
        assertThrows(IllegalStateException.class,
                () -> planner.plan(point(TaskFailurePhase.POLICY, null, "POLICY_EVALUATE", "", "")));
        assertThrows(IllegalStateException.class,
                () -> planner.plan(point(TaskFailurePhase.AGENT_ROLE, null,
                        "ROLE_EXECUTION:CODING_AGENT", "policy-1", "")));
        assertThrows(IllegalStateException.class,
                () -> planner.plan(point(TaskFailurePhase.PR_PUBLICATION, null, "PUBLICATION", "", "")));
        assertThrows(IllegalStateException.class,
                () -> planner.plan(point(TaskFailurePhase.AI_REVIEW, null, "AI_REVIEW", "", "")));
        assertThrows(IllegalStateException.class,
                () -> planner.plan(point(TaskFailurePhase.DETERMINISTIC_REVIEW, null,
                        "DETERMINISTIC_REVIEW", "", "")));
        assertThrows(IllegalStateException.class,
                () -> planner.plan(point(TaskFailurePhase.PLAN, null,
                        "PLAN_GENERATED", "policy-1", "")));
        assertThrows(IllegalStateException.class,
                () -> planner.plan(point(TaskFailurePhase.AI_REVIEW, AgentRole.CODING_AGENT,
                        "ROLE_EXECUTION:CODING_AGENT", "policy-1", "")));
        assertThrows(IllegalStateException.class,
                () -> planner.plan(point(TaskFailurePhase.AI_REVIEW, null,
                        "ROLE_EXECUTION:CODING_AGENT", "policy-1", "")));
        assertThrows(IllegalStateException.class,
                () -> planner.plan(pointWithAttempts(TaskFailurePhase.RAG, AgentRole.CODING_AGENT,
                        "stage-1", "", "ai-1", 17L, "command-1", "ROLE_EXECUTION:CODING_AGENT")));
        assertThrows(IllegalStateException.class,
                () -> planner.plan(pointWithAttempts(TaskFailurePhase.AGENT_ROLE, AgentRole.CODING_AGENT,
                        "", "retrieval-1", "ai-1", 17L, "command-1", "ROLE_EXECUTION:CODING_AGENT")));
        assertThrows(IllegalStateException.class,
                () -> planner.plan(pointWithAttempts(TaskFailurePhase.AI_REVIEW, null,
                        "stage-1", "retrieval-1", "", 17L, "command-1", "AI_REVIEW")));
        assertThrows(IllegalStateException.class,
                () -> planner.plan(pointWithAttempts(TaskFailurePhase.PLAN, null,
                        "", "", "", 0L, "command-1", "PLAN_GENERATED")));
        assertThrows(IllegalStateException.class,
                () -> planner.plan(pointWithAttempts(TaskFailurePhase.PLAN, null,
                        "", "", "", 17L, "", "PLAN_GENERATED")));
        assertThrows(IllegalStateException.class,
                () -> planner.plan(pointWithAttempts(TaskFailurePhase.PLAN, null,
                        "", "", "", 17L, "command-1", "")));
    }

    @Test
    void keepsBindingsCheckpointScopedImmutableAndParentAware() {
        InMemoryTaskRetryAttemptBindingStore store = new InMemoryTaskRetryAttemptBindingStore();
        TaskRetryAttemptBinding parent = new TaskRetryAttemptBinding(
                "binding-role", "checkpoint-1", TaskRetryAttemptKind.AGENT_STAGE,
                AgentRole.CODING_AGENT, "stage-2", "", 2, 1);
        TaskRetryAttemptBinding child = new TaskRetryAttemptBinding(
                "binding-rag", "checkpoint-1", TaskRetryAttemptKind.RETRIEVAL,
                AgentRole.CODING_AGENT, "retrieval-2", parent.bindingId(), 2, 0);
        store.save(parent);
        store.save(child);

        assertEquals(List.of(child, parent), store.listByCheckpoint("checkpoint-1"));
        assertEquals(parent, store.findPrimary("checkpoint-1", TaskRetryAttemptKind.AGENT_STAGE,
                AgentRole.CODING_AGENT).orElseThrow());
        assertEquals(child, store.findChild("checkpoint-1", parent.bindingId(),
                TaskRetryAttemptKind.RETRIEVAL, 0).orElseThrow());
        assertThrows(IllegalStateException.class, () -> store.save(new TaskRetryAttemptBinding(
                "binding-rag-other", "checkpoint-1", TaskRetryAttemptKind.RETRIEVAL,
                AgentRole.CODING_AGENT, "retrieval-3", parent.bindingId(), 3, 0)));
        assertThrows(IllegalStateException.class, () -> store.save(new TaskRetryAttemptBinding(
                "binding-role-other", "checkpoint-1", TaskRetryAttemptKind.AGENT_STAGE,
                AgentRole.CODING_AGENT, "stage-3", "", 3, 1)));

        TaskRetryAttemptBinding nextOrdinal = new TaskRetryAttemptBinding(
                "binding-role-next-ordinal", "checkpoint-1", TaskRetryAttemptKind.AGENT_STAGE,
                AgentRole.CODING_AGENT, "stage-3", "", 3, 2);
        store.save(nextOrdinal);
        assertThrows(IllegalStateException.class, () -> store.findPrimary(
                "checkpoint-1", TaskRetryAttemptKind.AGENT_STAGE, AgentRole.CODING_AGENT));

        TaskRetryAttemptBinding otherParent = new TaskRetryAttemptBinding(
                "binding-role-next", "checkpoint-2", TaskRetryAttemptKind.AGENT_STAGE,
                AgentRole.CODING_AGENT, "stage-4", "", 4, 0);
        store.save(otherParent);
        assertThrows(IllegalStateException.class, () -> store.save(new TaskRetryAttemptBinding(
                "binding-rag-reused-target", "checkpoint-2", TaskRetryAttemptKind.RETRIEVAL,
                AgentRole.CODING_AGENT, "retrieval-2", otherParent.bindingId(), 4, 0)));
        assertTrue(store.findById("binding-rag").isPresent());
    }

    @Test
    void checkpointGenerationIsExactOnlyForDurableFailureCommandAndStage() {
        TaskRetryPoint exact = point(TaskFailurePhase.PLAN, null, "PLAN_GENERATED", "", "");
        TaskRetryCheckpoint created = TaskRetryCheckpoint.created(
                "101", exact, 1, "retry-key", RdTaskStatus.FAILED_NEEDS_HUMAN, 1L);

        assertEquals(101L, created.businessGeneration());
        assertThrows(IllegalArgumentException.class, () -> TaskRetryCheckpoint.created(
                "checkpoint-1", exact, 1, "retry-key", RdTaskStatus.FAILED_NEEDS_HUMAN, 1L));
        assertThrows(IllegalArgumentException.class, () -> new TaskRetryCheckpoint(
                "101", "task-1", TaskFailurePhase.PLAN, null, "", "", "", 1, "retry-key",
                RdTaskStatus.FAILED_NEEDS_HUMAN, 11L, 17L, "command-1", "PLAN_GENERATED", "", "",
                "", "", "", 0L, 0L, "", 102L, "", List.of(), null, "", "", 1L, 1L));

        TaskRetryPoint legacy = new TaskRetryPoint("task-1", TaskFailurePhase.PLAN, null,
                "", "", "", "failed", 11L, 0L, "", "", "", "", "");
        assertEquals(0L, TaskRetryCheckpoint.created(
                "legacy-checkpoint", legacy, 1, "retry-key", RdTaskStatus.FAILED_NEEDS_HUMAN, 1L)
                .businessGeneration());
        assertThrows(IllegalArgumentException.class, () -> TaskRetryCheckpoint.created(
                "legacy-checkpoint", new TaskRetryPoint("task-1", TaskFailurePhase.PLAN, null,
                        "", "", "", "failed", 11L, 17L, "", "", "", "", ""),
                1, "retry-key", RdTaskStatus.FAILED_NEEDS_HUMAN, 1L));
        assertThrows(IllegalArgumentException.class, () -> new TaskRetryCheckpoint(
                "legacy-checkpoint", "task-1", TaskFailurePhase.PLAN, null, "", "", "", 1,
                "retry-key", RdTaskStatus.FAILED_NEEDS_HUMAN, 11L, 17L, "command-1", "", "", "",
                "", "", "", 0L, 0L, "", 0L, "", List.of(), null, "", "", 1L, 1L));
    }

    private void assertRoute(
            TaskRetryPoint point,
            String role,
            String stage,
            TaskRetryAttemptKind primaryKind,
            List<AgentRole> scope
    ) {
        var route = planner.plan(point);
        assertEquals(RdTaskStatus.RECOVERING, route.initialExpectedStatus());
        assertEquals(role, route.role());
        assertEquals(stage, route.firstStage());
        assertEquals(primaryKind, route.primaryAttemptKind());
        assertEquals(scope, route.precreatedRoles());
    }

    private TaskRetryPoint point(
            TaskFailurePhase phase,
            AgentRole role,
            String failedStage,
            String sourcePolicyRunId,
            String publicationOperationId
    ) {
        return new TaskRetryPoint("task-1", phase, role, "stage-1", "retrieval-1", "ai-1", "failed",
                11L, 17L, "command-1", failedStage, sourcePolicyRunId,
                sourcePolicyRunId.isBlank() ? "" : "sha256:" + "a".repeat(64), publicationOperationId);
    }

    private TaskRetryPoint pointWithAttempts(
            TaskFailurePhase phase,
            AgentRole role,
            String failedStageRunId,
            String failedRetrievalRunId,
            String failedAiReviewRunId,
            long sourceFence,
            String failedCommandId,
            String failedStage
    ) {
        String policyRunId = phase == TaskFailurePhase.PLAN ? "" : "policy-1";
        String planDigest = policyRunId.isBlank() ? "" : "sha256:" + "a".repeat(64);
        return new TaskRetryPoint("task-1", phase, role, failedStageRunId, failedRetrievalRunId,
                failedAiReviewRunId, "failed", 11L, sourceFence, failedCommandId, failedStage,
                policyRunId, planDigest, "");
    }
}
