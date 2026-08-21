package com.wish.rd.engine.requirement.job.model;

import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the durable, immutable stage-execution plan contract. */
class RequirementStageExecutionPlanTest {

    @Test
    void acceptsAllowedPolicyMultiEdgeAndDerivesPostConcurrency() {
        RequirementStageExecutionPlan plan = plan(
                RdTaskStatus.PLAN_GENERATED,
                transition(RdTaskStatus.PLAN_GENERATED, RdTaskStatus.WAITING_POLICY),
                transition(RdTaskStatus.WAITING_POLICY, RdTaskStatus.EXECUTING));

        assertEquals(9L, plan.postVersion());
        assertEquals(13L, plan.postFencingToken());
        assertEquals(RdTaskStatus.EXECUTING, plan.postStatus());
    }

    @Test
    void acceptsDeterministicReviewRejectionAndReconciledPublicationChains() {
        RequirementStageExecutionPlan rejected = plan(
                RdTaskStatus.EXECUTING,
                transition(RdTaskStatus.EXECUTING, RdTaskStatus.VALIDATING),
                transition(RdTaskStatus.VALIDATING, RdTaskStatus.REJECTED));
        RequirementStageExecutionPlan reconciled = plan(
                RdTaskStatus.REJECTED,
                transition(RdTaskStatus.REJECTED, RdTaskStatus.RECOVERING),
                transition(RdTaskStatus.RECOVERING, RdTaskStatus.PR_CREATING),
                transition(RdTaskStatus.PR_CREATING, RdTaskStatus.COMMITTED));

        assertEquals(RdTaskStatus.REJECTED, rejected.postStatus());
        assertEquals(RdTaskStatus.COMMITTED, reconciled.postStatus());
    }

    @Test
    void permitsSameStatusOnlyForExplicitSnapshotUpdate() {
        RequirementTaskMutation update = RequirementTaskMutation.snapshotUpdate(
                RdTaskStatus.EXECUTING, "", "{\"role\":\"done\"}", "", "", "role output");
        RequirementStageExecutionPlan plan = plan(RdTaskStatus.EXECUTING, update);

        assertEquals(RdTaskStatus.EXECUTING, plan.postStatus());
        assertThrows(IllegalArgumentException.class, () -> RequirementTaskMutation.statusTransition(
                RdTaskStatus.EXECUTING, RdTaskStatus.EXECUTING, "", "{}", "", "", ""));
    }

    @Test
    void rejectsIllegalEdgesAndBrokenChains() {
        assertThrows(IllegalStateException.class, () -> transition(
                RdTaskStatus.CREATED, RdTaskStatus.COMMITTED));
        assertThrows(IllegalArgumentException.class, () -> plan(
                RdTaskStatus.PLAN_GENERATED,
                transition(RdTaskStatus.PLAN_GENERATED, RdTaskStatus.WAITING_POLICY),
                transition(RdTaskStatus.EXECUTING, RdTaskStatus.VALIDATING)));
    }

    @Test
    void defensivelyCopiesMutationsAndContinuationCarriesNoTaskConcurrency() {
        List<RequirementTaskMutation> source = new ArrayList<>(List.of(
                transition(RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING)));
        RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                1, " task ", 7L, 11L, RdTaskStatus.CREATED, source,
                CommandDisposition.SUCCEEDED, new ContinuationSpec(" worker ", " next "),
                ExternalEffectReceipt.none());
        source.clear();

        assertEquals(1, plan.mutations().size());
        assertThrows(UnsupportedOperationException.class, () -> plan.mutations().clear());
        assertEquals("task", plan.taskId());
        assertEquals("worker", plan.continuation().role());
        assertFalse(plan.continuation().isTerminal());
    }

    @Test
    void validatesEffectReceiptsAndRejectsAmbiguousEffectsForFinalization() {
        assertTrue(ExternalEffectReceipt.none().isFinalizable());
        ExternalEffectReceipt confirmed = new ExternalEffectReceipt(
                ExternalEffectReceipt.Kind.PUBLICATION, "operation-1", "PR_CONFIRMED", "{}");
        assertTrue(confirmed.isFinalizable());
        ExternalEffectReceipt ambiguous = new ExternalEffectReceipt(
                ExternalEffectReceipt.Kind.PUBLICATION, "operation-1", "UNKNOWN_REMOTE_RESULT", "{}");
        assertFalse(ambiguous.isFinalizable());
        assertThrows(IllegalArgumentException.class, () -> new ExternalEffectReceipt(
                ExternalEffectReceipt.Kind.NONE, "operation", "PR_CONFIRMED", "{}"));
        assertThrows(IllegalArgumentException.class, () -> new ExternalEffectReceipt(
                ExternalEffectReceipt.Kind.PROVIDER_TOOL, "", "COMMITTED", "{}"));
        assertTrue(new ExternalEffectReceipt(
                ExternalEffectReceipt.Kind.PUBLICATION, "operation-1", "COMMITTED", "{}").isFinalizable());
        assertFalse(new ExternalEffectReceipt(
                ExternalEffectReceipt.Kind.PUBLICATION, "operation-1", "FAILED", "{}").isFinalizable());
        assertFalse(new ExternalEffectReceipt(
                ExternalEffectReceipt.Kind.PROVIDER_TOOL, "operation-1", "PREPARED", "{}").isFinalizable());
        assertTrue(new ExternalEffectReceipt(
                ExternalEffectReceipt.Kind.PROVIDER_TOOL, "operation-1", "COMMITTED", "{}").isFinalizable());
        ExternalEffectReceipt unknownPublication = new ExternalEffectReceipt(
                ExternalEffectReceipt.Kind.PUBLICATION, "operation-1", "UNKNOWN_REMOTE_RESULT", "{}");
        assertFalse(unknownPublication.isFinalizable());
        assertTrue(unknownPublication.allowsOutcomeRecording(CommandDisposition.RETRYABLE_TECHNICAL_FAILURE));
        assertTrue(unknownPublication.allowsOutcomeRecording(CommandDisposition.TERMINAL_FAILURE));
        assertFalse(unknownPublication.allowsOutcomeRecording(CommandDisposition.SUCCEEDED));
    }

    @Test
    void rejectsUnsupportedSchemasNegativeConcurrencyAndNonterminalFailureContinuations() {
        assertEquals(2, RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION);
        assertThrows(IllegalArgumentException.class, () -> new RequirementStageExecutionPlan(
                3, "task", 0L, 1L, RdTaskStatus.CREATED, List.of(),
                CommandDisposition.SUCCEEDED, ContinuationSpec.terminal(), ExternalEffectReceipt.none()));
        assertThrows(IllegalArgumentException.class, () -> new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION, "task", -1L, 1L,
                RdTaskStatus.CREATED, List.of(), CommandDisposition.SUCCEEDED,
                ContinuationSpec.terminal(), ExternalEffectReceipt.none()));
        assertThrows(IllegalArgumentException.class, () -> new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION, "task", 0L, -1L,
                RdTaskStatus.CREATED, List.of(), CommandDisposition.SUCCEEDED,
                ContinuationSpec.terminal(), ExternalEffectReceipt.none()));
        assertThrows(IllegalArgumentException.class, () -> new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION, "task", 0L, 1L,
                RdTaskStatus.CREATED, List.of(), CommandDisposition.TERMINAL_FAILURE,
                new ContinuationSpec("role", "stage"), ExternalEffectReceipt.none()));
        assertThrows(IllegalArgumentException.class, () -> new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION, "task", 0L, 1L,
                RdTaskStatus.CREATED, List.of(), CommandDisposition.RETRYABLE_TECHNICAL_FAILURE,
                new ContinuationSpec("role", "stage"), ExternalEffectReceipt.none()));
    }

    @Test
    void preservesBlankPayloadsAndRejectsNegativeStageCommandConcurrency() {
        RequirementTaskMutation preserve = RequirementTaskMutation.statusTransition(
                RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING, "", "", "", "", "");

        assertEquals("", preserve.executionResultJson());
        assertThrows(IllegalArgumentException.class, () -> RequirementStageCommand.pending(
                "negative-version", "task", -1L, 1L, "role", "stage", 0, 3, 0L,
                ScheduleResourceClass.GENERIC, "_default", "", "P2", 1L));
        assertThrows(IllegalArgumentException.class, () -> RequirementStageCommand.pending(
                "negative-fence", "task", 0L, -1L, "role", "stage", 0, 3, 0L,
                ScheduleResourceClass.GENERIC, "_default", "", "P2", 1L));
        assertThrows(IllegalArgumentException.class, () -> RequirementStageCommand.pending(
                "zero-fence", "task", 0L, 0L, "role", "stage", 0, 3, 0L,
                ScheduleResourceClass.GENERIC, "_default", "", "P2", 1L));
    }

    @Test
    void rejectsZeroPlanFenceAndMissingExplicitEffectReceipt() {
        assertThrows(IllegalArgumentException.class, () -> new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION, "task", 0L, 0L,
                RdTaskStatus.CREATED, List.of(), CommandDisposition.SUCCEEDED,
                ContinuationSpec.terminal(), ExternalEffectReceipt.none()));
        assertThrows(IllegalArgumentException.class, () -> new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION, "task", 0L, 1L,
                RdTaskStatus.CREATED, List.of(), CommandDisposition.SUCCEEDED,
                ContinuationSpec.terminal(), null));
        assertThrows(IllegalArgumentException.class, () -> new ExternalEffectReceipt(
                null, "", "", "{}"));
    }

    @Test
    void exposesNullPreserveReplacementsButKeepsBlankErrorAsAnExplicitClear() {
        RequirementTaskMutation mutation = RequirementTaskMutation.statusTransition(
                RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING, "", "", "", "", "event");

        assertNull(mutation.promptSnapshotReplacementOrNull());
        assertNull(mutation.executionResultReplacementOrNull());
        assertNull(mutation.pullRequestUrlReplacementOrNull());
        assertEquals("", mutation.errorMessage());
    }

    private static RequirementStageExecutionPlan plan(
            RdTaskStatus expectedStatus,
            RequirementTaskMutation... mutations
    ) {
        return new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION, "task", 7L, 11L, expectedStatus, List.of(mutations),
                CommandDisposition.SUCCEEDED, ContinuationSpec.terminal(), ExternalEffectReceipt.none());
    }

    private static RequirementTaskMutation transition(RdTaskStatus from, RdTaskStatus to) {
        return RequirementTaskMutation.statusTransition(from, to, "", "{}", "", "", to.name());
    }
}
