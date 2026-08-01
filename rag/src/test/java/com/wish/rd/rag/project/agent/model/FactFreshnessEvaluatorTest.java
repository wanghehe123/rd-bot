package com.wish.rd.rag.project.agent.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactFreshnessEvaluatorTest {

    private final FactFreshnessEvaluator.FreshnessContext context = new FactFreshnessEvaluator.FreshnessContext(
            "abc123",
            "ws-1",
            Instant.parse("2026-08-01T09:00:00Z"),
            FactFreshnessEvaluator.DEFAULT_MAX_TTL
    );

    @Test
    void sameRevisionFactIsFreshWhenRevisionMatches() {
        RoleExecutionFact fact = fact(FactFreshnessPolicy.SAME_REVISION, "abc123", null, null);
        assertEquals(FactFreshnessStatus.FRESH, FactFreshnessEvaluator.evaluate(fact, context));
        assertTrue(FactFreshnessEvaluator.isPromptEligible(fact, context));
    }

    @Test
    void sameRevisionFactIsStaleWhenRevisionDiffers() {
        RoleExecutionFact fact = fact(FactFreshnessPolicy.SAME_REVISION, "other", null, null);
        assertEquals(FactFreshnessStatus.STALE, FactFreshnessEvaluator.evaluate(fact, context));
    }

    @Test
    void ttlFactExpiresAndAlwaysRecheckStaysStale() {
        RoleExecutionFact ttl = new RoleExecutionFact(
                "fact-ttl",
                FactKind.OBSERVED,
                "ttl fact",
                "artifact-1",
                "stage-1",
                null,
                "abc123",
                null,
                "2026-08-01T08:00:00Z",
                null,
                FactFreshnessPolicy.TTL,
                "2026-08-01T10:00:00Z",
                null,
                FactFreshnessStatus.FRESH
        );
        assertEquals(FactFreshnessStatus.FRESH, FactFreshnessEvaluator.evaluate(ttl, context));

        RoleExecutionFact alwaysRecheck = fact(FactFreshnessPolicy.ALWAYS_RECHECK, "abc123", null, null);
        assertEquals(FactFreshnessStatus.STALE, FactFreshnessEvaluator.evaluate(alwaysRecheck, context));
    }

    @Test
    void sameWorkspaceRequiresMatchingFingerprint() {
        RoleExecutionFact fresh = new RoleExecutionFact(
                "fact-ws",
                FactKind.OBSERVED,
                "workspace fact",
                "artifact-1",
                "stage-1",
                null,
                null,
                "ws-1",
                "2026-08-01T08:00:00Z",
                null,
                FactFreshnessPolicy.SAME_WORKSPACE,
                null,
                null,
                FactFreshnessStatus.FRESH
        );
        assertEquals(FactFreshnessStatus.FRESH, FactFreshnessEvaluator.evaluate(fresh, context));

        RoleExecutionFact stale = new RoleExecutionFact(
                "fact-ws-stale",
                FactKind.OBSERVED,
                "workspace fact",
                "artifact-1",
                "stage-1",
                null,
                null,
                "ws-2",
                "2026-08-01T08:00:00Z",
                null,
                FactFreshnessPolicy.SAME_WORKSPACE,
                null,
                null,
                FactFreshnessStatus.FRESH
        );
        assertEquals(FactFreshnessStatus.STALE, FactFreshnessEvaluator.evaluate(stale, context));
        assertFalse(FactFreshnessEvaluator.isPromptEligible(stale, context));
    }

    private static RoleExecutionFact fact(
            FactFreshnessPolicy policy,
            String revision,
            String workspaceFingerprint,
            String expiresAt
    ) {
        return new RoleExecutionFact(
                "fact-1",
                FactKind.OBSERVED,
                "observed statement",
                "artifact-1",
                "stage-1",
                null,
                revision,
                workspaceFingerprint,
                "2026-08-01T08:00:00Z",
                null,
                policy,
                expiresAt,
                null,
                FactFreshnessStatus.FRESH
        );
    }
}
