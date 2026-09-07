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
        assertEquals("PI_BRIDGE_PROTOCOL", PiBridgeResultPayloads.failureCategory(raw));
    }

    @Test
    void shouldKeepBudgetExceededCategoryIdentifiableInsteadOfCollapsingItIntoProtocol() {
        String raw = """
                {
                  "status": "FAILED",
                  "summary": "Agent stopped after exhausting the coding-benchmark turn/token budget",
                  "failureCategory": "BUDGET_EXCEEDED",
                  "errorMessage": "BUDGET_EXCEEDED: maxAgentTurns=40 turns=40 cumulativeUsage=123456"
                }
                """;

        assertTrue(PiBridgeResultPayloads.isSyntheticFailure(raw));
        assertEquals(
                "BUDGET_EXCEEDED: maxAgentTurns=40 turns=40 cumulativeUsage=123456",
                PiBridgeResultPayloads.failureMessage(raw)
        );
        assertEquals("BUDGET_EXCEEDED", PiBridgeResultPayloads.failureCategory(raw));
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
        assertTrue(PiBridgeResultPayloads.failureCategory(raw).isBlank());
    }
}
