package com.wish.rd.engine.retry;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.job.RequirementStageFinalizationPort.ExhaustionProvenanceDraft;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryAttemptBindingStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryCheckpointStore;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryAttemptBinding;
import com.wish.rd.engine.retry.model.TaskRetryAttemptKind;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskRetryExhaustionProvenanceFactoryTest {

    private final InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
    private final InMemoryTaskRetryAttemptBindingStore bindings = new InMemoryTaskRetryAttemptBindingStore();
    private final TaskRetryExhaustionProvenanceFactory factory =
            new TaskRetryExhaustionProvenanceFactory(checkpoints, bindings);

    @Test
    void derivesFailurePhaseForEveryFrozenStageFamily() {
        assertEquals(TaskFailurePhase.MATERIAL, phaseOf("MATERIAL_COLLECTING"));
        assertEquals(TaskFailurePhase.MATERIAL, phaseOf("MATERIAL_READY"));
        assertEquals(TaskFailurePhase.CONTEXT, phaseOf("CONTEXT_BUILDING"));
        assertEquals(TaskFailurePhase.CONTEXT, phaseOf("CONTEXT_READY"));
        assertEquals(TaskFailurePhase.PLAN, phaseOf("PLAN_GENERATING"));
        assertEquals(TaskFailurePhase.PLAN, phaseOf("PLAN_GENERATED"));
        assertEquals(TaskFailurePhase.POLICY, phaseOf("POLICY_EVALUATE"));
        assertEquals(TaskFailurePhase.POLICY, phaseOf("POLICY_APPLY"));
        assertEquals(TaskFailurePhase.POLICY, phaseOf("APPROVAL_RESUME"));
        assertEquals(TaskFailurePhase.DETERMINISTIC_REVIEW, phaseOf("DETERMINISTIC_REVIEW"));
        assertEquals(TaskFailurePhase.AI_REVIEW, phaseOf("AI_REVIEW"));
        assertEquals(TaskFailurePhase.PR_PUBLICATION, phaseOf("PUBLICATION:operation-1"));
        assertEquals(TaskFailurePhase.PR_PUBLICATION, phaseOf("PUBLICATION"));
        assertEquals(TaskFailurePhase.AGENT_ROLE, phaseOf("ROLE_EXECUTION:CODING_AGENT"));
        assertEquals(TaskFailurePhase.RAG, TaskRetryRoutePlanner.phaseForStage(
                "ROLE_EXECUTION:CODING_AGENT", TaskFailurePhase.RAG));
    }

    @Test
    void canonicalizesBarePublicationFailedStageAndRequiresOperationId() {
        ExhaustionProvenanceDraft draft = factory.draftFor(
                command("PUBLICATION", "", ""), "prov-publication", "UNKNOWN_REMOTE_RESULT",
                new TaskRetryExhaustionProvenanceFactory.ExecutionContext(
                        "policy-1", "sha256:" + "b".repeat(64), "operation-1", "", "", ""));

        assertEquals(TaskFailurePhase.PR_PUBLICATION, draft.failurePhase());
        assertEquals("PUBLICATION:operation-1", draft.failedStage());
        assertEquals("operation-1", draft.publicationOperationId());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> factory.draftFor(command("PUBLICATION", "", ""), "prov-missing-op",
                        "UNKNOWN_REMOTE_RESULT",
                        new TaskRetryExhaustionProvenanceFactory.ExecutionContext(
                                "policy-1", "sha256:" + "b".repeat(64), "", "", "", "")));
        assertEquals("publication exhaustion requires operation id: command-1", failure.getMessage());
    }

    @Test
    void rejectsAnUnmappableStageInsteadOfDegradingToAGenericContextPhase() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> factory.draftFor(command("UNKNOWN_STAGE", "", ""), "prov-1", "TECHNICAL_EXHAUSTED",
                        TaskRetryExhaustionProvenanceFactory.ExecutionContext.empty()));
        assertEquals("unmappable exhausted command stage: UNKNOWN_STAGE", failure.getMessage());
    }

    @Test
    void inheritsCheckpointLineageAndTargetBindingAttemptIdentity() {
        TaskRetryCheckpoint checkpoint = new TaskRetryCheckpoint(
                "101", "task-1", TaskFailurePhase.AGENT_ROLE, AgentRole.CODING_AGENT,
                "coding-1", "", "", 1, "task-1:11:AGENT_ROLE:CODING_AGENT",
                RdTaskStatus.FAILED_NEEDS_HUMAN, 11L, 12L,
                "command-old", "ROLE_EXECUTION:CODING_AGENT", "policy-1", "",
                "sha256:" + "a".repeat(64), "", "", 0L, 0L, "", 101L,
                "", List.of(), TaskRetryCheckpointStatus.CREATED, "failed", "", 1L, 1L);
        checkpoints.createOrGet(checkpoint);
        bindings.save(new TaskRetryAttemptBinding(
                "binding-1", "101", TaskRetryAttemptKind.AGENT_STAGE,
                AgentRole.CODING_AGENT, "coding-2", "", 2, 0));
        RequirementStageCommand exhausted = command(
                "ROLE_EXECUTION:CODING_AGENT", "101", "binding-1");

        ExhaustionProvenanceDraft draft = factory.draftFor(
                exhausted, "prov-role", "TECHNICAL_EXHAUSTED",
                TaskRetryExhaustionProvenanceFactory.ExecutionContext.empty());

        assertEquals(TaskFailurePhase.AGENT_ROLE, draft.failurePhase());
        assertEquals("ROLE_EXECUTION:CODING_AGENT", draft.failedStage());
        assertEquals("coding-2", draft.failedStageRunId());
        assertEquals("policy-1", draft.sourcePolicyRunId());
        assertEquals("sha256:" + "a".repeat(64), draft.sourcePlanDigest());
    }

    private TaskFailurePhase phaseOf(String stage) {
        return factory.draftFor(command(stage, "", ""), "prov-" + stage, "TECHNICAL_EXHAUSTED",
                new TaskRetryExhaustionProvenanceFactory.ExecutionContext(
                        needsPolicy(stage) ? "policy-1" : "",
                        needsPolicy(stage) ? "sha256:" + "b".repeat(64) : "",
                        stage.startsWith("PUBLICATION") ? "operation-1" : "",
                        "", "", "")).failurePhase();
    }

    private static boolean needsPolicy(String stage) {
        return !(stage.startsWith("MATERIAL_") || stage.startsWith("CONTEXT_") || stage.startsWith("PLAN_"));
    }

    private static RequirementStageCommand command(
            String stage, String retryCheckpointId, String targetBindingId
    ) {
        long generation = retryCheckpointId.isBlank() ? 0L : Long.parseLong(retryCheckpointId);
        return RequirementStageCommand.pending(
                "command-1", "task-1", 12L, 13L, "REQUIREMENT_DELIVERY", stage,
                1, 1, 2_000L, ScheduleResourceClass.GENERIC, Set.of(ScheduleResourceClass.GENERIC),
                "project-1", "", "P1", "", retryCheckpointId, generation, targetBindingId, 1L);
    }
}
