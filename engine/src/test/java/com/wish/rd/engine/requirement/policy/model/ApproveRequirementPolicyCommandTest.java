package com.wish.rd.engine.requirement.policy.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract for a digest-bound approval write request. */
class ApproveRequirementPolicyCommandTest {

    private static final String DIGEST = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void acceptsOnlyApprovedDecisionAndPositiveFencingToken() {
        assertDoesNotThrow(() -> command("APPROVED", 1L));
        assertThrows(IllegalArgumentException.class, () -> command("DENIED", 1L));
        assertThrows(IllegalArgumentException.class, () -> command("APPROVED", 0L));
    }

    private static ApproveRequirementPolicyCommand command(String decision, long fencingToken) {
        return new ApproveRequirementPolicyCommand(
                "1", "2", 0L, fencingToken, DIGEST, DIGEST, "request-1", decision, "approved");
    }
}
