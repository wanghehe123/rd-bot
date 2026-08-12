package com.wish.rd.engine.requirement.policy.model;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract tests for ledger-bound approval producer results. */
class RequirementPolicyApprovalResultContractTest {
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void bindsApprovedLedgerTaskResumePolicyRunStatusAndConcurrencyPair() {
        assertDoesNotThrow(() -> new RequirementPolicyApprovalResult(approved(), resume("101"), 6L, 8L));
        assertThrows(IllegalArgumentException.class,
                () -> new RequirementPolicyApprovalResult(approved(), resume("other-run"), 6L, 8L));
        assertThrows(IllegalArgumentException.class,
                () -> new RequirementPolicyApprovalResult(approved(), wrongStage(), 6L, 8L));
        assertThrows(IllegalArgumentException.class,
                () -> new RequirementPolicyApprovalResult(approved(), resume("101").succeeded(NOW), 6L, 8L));
    }

    private static RequirementPolicyRun approved() {
        String plan = "{\"plan\":true}";
        String policy = "{\"action\":\"WAITING_APPROVAL\"}";
        return new RequirementPolicyRun("101", "201", 3L, 5L, plan, RequirementPolicyRun.canonicalJsonDigest(plan),
                policy, RequirementPolicyRun.canonicalJsonDigest(policy), "WAITING_APPROVAL", RequirementPolicyRunState.APPROVED,
                6L, 8L, 5L, 7L, "request", "host", "safe", NOW, "901", "", 0L,
                1L, NOW, NOW);
    }

    private static RequirementStageCommand resume(String policyRunId) {
        return RequirementStageCommand.pending("901", "201", 6L, 8L, "REQUIREMENT_DELIVERY", "APPROVAL_RESUME",
                0, 3, NOW + 1_000L, ScheduleResourceClass.GENERIC, Set.of(ScheduleResourceClass.GENERIC),
                "project-1", "provider", "P1", policyRunId, NOW);
    }

    private static RequirementStageCommand wrongStage() {
        return RequirementStageCommand.pending("901", "201", 6L, 8L, "REQUIREMENT_DELIVERY", "POLICY_APPLY",
                0, 3, NOW + 1_000L, ScheduleResourceClass.GENERIC, Set.of(ScheduleResourceClass.GENERIC),
                "project-1", "provider", "P1", "101", NOW);
    }
}
