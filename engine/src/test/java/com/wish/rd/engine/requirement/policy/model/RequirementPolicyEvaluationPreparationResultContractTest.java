package com.wish.rd.engine.requirement.policy.model;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract for the checkpoint-aware frozen-plan/evaluate-command producer result. */
class RequirementPolicyEvaluationPreparationResultContractTest {
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void requiresTheEvaluateCommandToCarryTheExactRetryControlIdentity() {
        RequirementPolicyRetryContext context = new RequirementPolicyRetryContext(
                "101", 101L, "binding-reviewer", "source-policy", plan().planDigest(),
                TaskRetryCheckpointStatus.DISPATCHED);

        assertDoesNotThrow(() -> new RequirementPolicyEvaluationPreparationResult(plan(), evaluate(context, ""), context));
        assertThrows(IllegalArgumentException.class,
                () -> new RequirementPolicyEvaluationPreparationResult(plan(), evaluate(context, "binding-reviewer"), context));
    }

    private static RequirementPolicyRun plan() {
        String plan = "{\"plan\":true}";
        return new RequirementPolicyRun("101", "201", 5L, 7L, plan, RequirementPolicyRun.canonicalJsonDigest(plan),
                "", "", "", RequirementPolicyRunState.PLAN_READY, 5L, 7L,
                null, null, "", "", "", 0L, "", "", 0L, 0L, NOW, NOW);
    }

    private static RequirementStageCommand evaluate(RequirementPolicyRetryContext context, String targetBindingId) {
        return RequirementStageCommand.pending("101", "201", 5L, 7L, "REQUIREMENT_DELIVERY", "POLICY_EVALUATE",
                0, 3, NOW + 1_000L, ScheduleResourceClass.GENERIC, Set.of(ScheduleResourceClass.GENERIC),
                "project-1", "provider", "P1", "101", context.checkpointId(), context.businessGeneration(),
                targetBindingId, NOW);
    }
}
