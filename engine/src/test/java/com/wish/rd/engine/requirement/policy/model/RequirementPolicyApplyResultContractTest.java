package com.wish.rd.engine.requirement.policy.model;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract for disposition-specific atomic policy-apply outcomes. */
class RequirementPolicyApplyResultContractTest {
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void allowsOnlyAllowedOutcomesToExposeTheFirstDeliveryContinuation() {
        assertDoesNotThrow(() -> new RequirementPolicyApplyResult(
                applied(), completedApply(), RequirementPolicyApplyDisposition.ALLOWED,
                firstContinuation(), 7L, 9L));
        assertDoesNotThrow(() -> new RequirementPolicyApplyResult(
                waitingApproval(), completedApply(), RequirementPolicyApplyDisposition.WAITING_APPROVAL,
                null, 7L, 9L));
        assertDoesNotThrow(() -> new RequirementPolicyApplyResult(
                denied(), completedApply(), RequirementPolicyApplyDisposition.DENIED,
                null, 7L, 9L));
        assertThrows(IllegalArgumentException.class, () -> new RequirementPolicyApplyResult(
                waitingApproval(), completedApply(), RequirementPolicyApplyDisposition.WAITING_APPROVAL,
                firstContinuation(), 7L, 9L));
        assertThrows(IllegalArgumentException.class, () -> new RequirementPolicyApplyResult(
                denied(), completedApply(), RequirementPolicyApplyDisposition.ALLOWED,
                firstContinuation(), 7L, 9L));
    }

    private static RequirementPolicyRun applied() {
        return run(RequirementPolicyRunState.APPLIED, "ALLOWED", "902", NOW);
    }

    private static RequirementPolicyRun waitingApproval() {
        return run(RequirementPolicyRunState.WAITING_APPROVAL, "WAITING_APPROVAL", "", 0L);
    }

    private static RequirementPolicyRun denied() {
        return run(RequirementPolicyRunState.DENIED, "UNSAFE", "902", NOW);
    }

    private static RequirementPolicyRun run(RequirementPolicyRunState state, String action, String consumed, long consumedAt) {
        String plan = "{\"plan\":true}";
        String policy = "{\"policyAction\":\"" + action + "\"}";
        return new RequirementPolicyRun(
                "101", "201", 5L, 7L, plan, RequirementPolicyRun.canonicalJsonDigest(plan),
                policy, RequirementPolicyRun.canonicalJsonDigest(policy), action, state,
                7L, 9L, null, null, "", "", "", 0L, "", consumed, consumedAt,
                2L, NOW, NOW);
    }

    private static RequirementStageCommand completedApply() {
        return RequirementStageCommand.pending("902", "201", 6L, 8L,
                "REQUIREMENT_DELIVERY", "POLICY_APPLY", 0, 3, NOW + 1_000L,
                ScheduleResourceClass.GENERIC, Set.of(ScheduleResourceClass.GENERIC),
                "project-1", "provider", "P1", "101", NOW)
                .claimed("worker", NOW + 100L, NOW).succeeded(NOW + 1L);
    }

    private static RequirementStageCommand firstContinuation() {
        return RequirementStageCommand.pending("903", "201", 7L, 9L,
                "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER", 0, 3, NOW + 1_000L,
                ScheduleResourceClass.PROVIDER, Set.of(ScheduleResourceClass.PROVIDER),
                "project-1", "provider", "P1", "101", NOW);
    }
}
