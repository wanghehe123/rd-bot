package com.wish.rd.engine.requirement.model;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkArm;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Host-verification flags and factory arms on {@link AgentWorkflowPlan}. */
class AgentWorkflowPlanTest {

    @Test
    void productionPlanEnablesHostVerifyWithTwoPasses() {
        AgentWorkflowPlan plan = AgentWorkflowPlan.production();
        assertTrue(plan.hostVerifyRemediationAllowed());
        assertEquals(2, plan.hostVerifyMaxRemediationPasses());
        assertEquals(1, plan.qaMaxRemediationPasses());
    }

    @Test
    void hostVerifyDisabledCannotRaisePassCap() {
        assertThrows(IllegalArgumentException.class, () -> new AgentWorkflowPlan(
                AgentRole.requirementDeliveryOrder(),
                true,
                true,
                1,
                false,
                3,
                ledgerOfProduction(),
                "BAD"));
    }

    @Test
    void ablationArmsDisableHostVerifyButKeepDefaultPassCap() {
        for (CodingBenchmarkArm arm : new CodingBenchmarkArm[] {
                CodingBenchmarkArm.A, CodingBenchmarkArm.B, CodingBenchmarkArm.C
        }) {
            AgentWorkflowPlan plan = AgentWorkflowPlan.codingBenchmark(arm);
            assertFalse(plan.hostVerifyRemediationEnabled(), arm.name());
            assertFalse(plan.hostVerifyRemediationAllowed(), arm.name());
            assertEquals(AgentWorkflowPlan.DEFAULT_HOST_VERIFY_REMEDIATION_PASSES,
                    plan.hostVerifyMaxRemediationPasses(), arm.name());
        }
    }

    @Test
    void hostVerifyEnabledRequiresCodingAgentAndPositivePasses() {
        Map<AgentRole, Double> reviewerLedger = Map.of(AgentRole.REQUIREMENT_REVIEWER, 1.0d);
        assertThrows(IllegalArgumentException.class, () -> new AgentWorkflowPlan(
                AgentRole.requirementDeliveryOrder(),
                true,
                true,
                1,
                true,
                0,
                ledgerOfProduction(),
                "ZERO_PASSES"));
        assertThrows(IllegalArgumentException.class, () -> new AgentWorkflowPlan(
                java.util.List.of(AgentRole.REQUIREMENT_REVIEWER),
                false,
                false,
                1,
                true,
                2,
                reviewerLedger,
                "NO_CODING"));
        assertThrows(IllegalArgumentException.class, () -> new AgentWorkflowPlan(
                AgentRole.requirementDeliveryOrder(),
                true,
                true,
                1,
                false,
                -1,
                ledgerOfProduction(),
                "NEGATIVE"));
    }

    private static Map<AgentRole, Double> ledgerOfProduction() {
        Map<AgentRole, Double> ledger = new LinkedHashMap<>();
        ledger.put(AgentRole.REQUIREMENT_REVIEWER, 0.08d);
        ledger.put(AgentRole.SOLUTION_ARCHITECT, 0.16d);
        ledger.put(AgentRole.CODING_AGENT, 0.56d);
        ledger.put(AgentRole.QA_AGENT, 0.08d);
        return ledger;
    }
}
