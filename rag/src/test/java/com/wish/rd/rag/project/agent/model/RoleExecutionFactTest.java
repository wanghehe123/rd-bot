package com.wish.rd.rag.project.agent.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleExecutionFactTest {

    @Test
    void derivesEnvironmentNotesFromFreshObservedFacts() {
        RoleExecutionFact fresh = new RoleExecutionFact(
                "fact-b",
                FactKind.OBSERVED,
                "second note",
                "artifact-1",
                "stage-1",
                null,
                "abc123",
                null,
                "2026-08-01T08:00:00Z",
                null,
                FactFreshnessPolicy.SAME_REVISION,
                null,
                null,
                FactFreshnessStatus.FRESH
        );
        RoleExecutionFact stale = new RoleExecutionFact(
                "fact-a",
                FactKind.OBSERVED,
                "stale note",
                "artifact-2",
                "stage-1",
                null,
                "old",
                null,
                "2026-08-01T08:00:00Z",
                null,
                FactFreshnessPolicy.SAME_REVISION,
                null,
                null,
                FactFreshnessStatus.FRESH
        );
        FactFreshnessEvaluator.FreshnessContext context = new FactFreshnessEvaluator.FreshnessContext(
                "abc123",
                null,
                Instant.parse("2026-08-01T09:00:00Z"),
                FactFreshnessEvaluator.DEFAULT_MAX_TTL
        );
        assertEquals(List.of("second note"), AgentFactCanonical.deriveEnvironmentNotes(List.of(fresh, stale), context));
    }

    @Test
    void rejectsOversizedStatementsAndProducesStableCanonicalHash() {
        String longStatement = "x".repeat(RoleExecutionFact.MAX_STATEMENT_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> new RoleExecutionFact(
                "fact-1",
                FactKind.DECLARED,
                longStatement,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                FactFreshnessPolicy.ALWAYS_RECHECK,
                null,
                null,
                FactFreshnessStatus.FRESH
        ));

        RoleExecutionFact fact = new RoleExecutionFact(
                "fact-1",
                FactKind.OBSERVED,
                "token: secret-value",
                "artifact-1",
                "stage-1",
                null,
                "abc123",
                null,
                "2026-08-01T08:00:00Z",
                null,
                FactFreshnessPolicy.SAME_REVISION,
                null,
                null,
                FactFreshnessStatus.FRESH
        );
        String hash = AgentFactCanonical.canonicalFactHash(fact);
        assertNotEquals("", hash);
        assertTrue(AgentFactCanonical.canonicalFactJson(fact).contains("[REDACTED]"));
    }
}
