package com.wish.rd.exec.repair.pi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiBridgeResultPayloadsTest {

    @Test
    void shouldSurfaceBridgeProtocolFailureInsteadOfRoleSchemaErrors() {
        String raw = """
                {
                  "status": "FAILED",
                  "summary": "Pi bridge failed before a structured agent result was accepted",
                  "failureCategory": "PI_BRIDGE_PROTOCOL",
                  "errorMessage": "Pi session settled without rd_submit_result"
                }
                """;

        assertTrue(PiBridgeResultPayloads.isSyntheticFailure(raw));
        assertEquals(
                "Pi session settled without rd_submit_result",
                PiBridgeResultPayloads.failureMessage(raw)
        );
    }

    @Test
    void shouldNotTreatARealArchitectResultAsSyntheticFailure() {
        String raw = """
                {
                  "summary": "plan",
                  "affectedFiles": ["a.ts"],
                  "implementationSteps": ["do it"],
                  "acceptanceMapping": [{"criteria": "x", "validation": "y"}],
                  "testPlan": [{"criteria": "x", "command": "npm test"}]
                }
                """;

        assertTrue(PiBridgeResultPayloads.failureMessage(raw).isBlank());
    }
}
