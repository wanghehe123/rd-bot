package com.wish.rd.engine.requirement.policy.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract for the externally-evaluated, canonical policy decision input. */
class RecordRequirementPolicyDecisionCommandTest {

    private static final String PLAN = "{\"plan\":true}";
    private static final String POLICY = "{\"policyAction\":\"ALLOWED\",\"reason\":\"safe\"}";

    @Test
    void acceptsOnlyCanonicalDigestBoundDecisionInput() {
        assertDoesNotThrow(() -> command(POLICY, "ALLOWED", 7L));
        assertThrows(IllegalArgumentException.class, () -> command(
                "{\"reason\":\"safe\",\"policyAction\":\"ALLOWED\"}", "ALLOWED", 7L));
        assertThrows(IllegalArgumentException.class, () -> command(
                " {\"policyAction\":\"ALLOWED\",\"reason\":\"safe\"}", "ALLOWED", 7L));
        assertThrows(IllegalArgumentException.class, () -> command(POLICY, "", 7L));
        assertThrows(IllegalArgumentException.class, () -> command(POLICY, "UNSAFE", 7L));
        assertThrows(IllegalArgumentException.class, () -> command(
                "{\"policyAction\":\"UNKNOWN\",\"reason\":\"safe\"}", "UNKNOWN", 7L));
        assertThrows(IllegalArgumentException.class, () -> command(POLICY, "ALLOWED", 0L));
    }

    private static RecordRequirementPolicyDecisionCommand command(
            String policyJson, String action, long fencingToken
    ) {
        return new RecordRequirementPolicyDecisionCommand(
                "101", "201", 5L, fencingToken,
                RequirementPolicyRun.canonicalJsonDigest(PLAN),
                policyJson, RequirementPolicyRun.canonicalJsonDigest(policyJson), action);
    }
}
