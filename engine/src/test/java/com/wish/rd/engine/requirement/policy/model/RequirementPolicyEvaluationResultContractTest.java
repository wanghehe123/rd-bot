package com.wish.rd.engine.requirement.policy.model;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract for the atomic policy-evaluation producer result. */
class RequirementPolicyEvaluationResultContractTest {
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void acceptsOnlyCompletedPolicyEvaluateAndCleanPolicyApplyForTheDecidedLedger() {
        assertDoesNotThrow(() -> new RequirementPolicyEvaluationResult(
                decided(), completedEvaluate(), pendingApply(6L, 8L), 6L, 8L));
        assertThrows(IllegalArgumentException.class, () -> new RequirementPolicyEvaluationResult(
                decided(), completedEvaluate(), pendingApply(6L, 9L), 6L, 8L));
        assertThrows(IllegalArgumentException.class, () -> new RequirementPolicyEvaluationResult(
                decided(), pending("REQUIREMENT_DELIVERY", "POLICY_EVALUATE", 5L, 7L),
                pendingApply(6L, 8L), 6L, 8L));
        RequirementPolicyRun skippedGeneration = new RequirementPolicyRun(
                decided().id(), decided().taskId(), 6L, 8L, decided().planJson(), decided().planDigest(),
                decided().policyJson(), decided().policyDigest(), decided().policyAction(),
                RequirementPolicyRunState.POLICY_DECIDED, 7L, 9L,
                null, null, "", "", "", 0L, "", "", 0L, 1L, NOW, NOW + 1L);
        assertThrows(IllegalArgumentException.class, () -> new RequirementPolicyEvaluationResult(
                skippedGeneration, completedEvaluate(), pendingApply(7L, 9L), 7L, 9L));
    }

    private static RequirementPolicyRun decided() {
        String plan = "{\"plan\":true}";
        String policy = "{\"policyAction\":\"ALLOWED\",\"reason\":\"safe\"}";
        return new RequirementPolicyRun(
                "101", "201", 5L, 7L, plan, RequirementPolicyRun.canonicalJsonDigest(plan),
                policy, RequirementPolicyRun.canonicalJsonDigest(policy), "ALLOWED",
                RequirementPolicyRunState.POLICY_DECIDED, 6L, 8L,
                null, null, "", "", "", 0L, "", "", 0L, 1L, NOW, NOW + 1L);
    }

    private static RequirementStageCommand completedEvaluate() {
        return pending("REQUIREMENT_DELIVERY", "POLICY_EVALUATE", 5L, 7L)
                .claimed("worker", NOW + 10_000L, NOW).succeeded(NOW + 1L);
    }

    private static RequirementStageCommand pendingApply(long version, long fence) {
        return pending("REQUIREMENT_DELIVERY", "POLICY_APPLY", version, fence);
    }

    private static RequirementStageCommand pending(String role, String stage, long version, long fence) {
        return RequirementStageCommand.pending(
                stage.equals("POLICY_EVALUATE") ? "901" : "902", "201", version, fence,
                role, stage, 0, 3, NOW + 1_000L, ScheduleResourceClass.GENERIC,
                Set.of(ScheduleResourceClass.GENERIC), "project-1", "provider", "P1", "101", NOW);
    }
}
