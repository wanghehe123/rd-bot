package com.wish.rd.rag.project.agent.model;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/** Harness-side freshness evaluation for role execution facts. */
public final class FactFreshnessEvaluator {

    public static final Duration DEFAULT_MAX_TTL = Duration.ofDays(7);

    private FactFreshnessEvaluator() {
    }

    /**
     * Context used when evaluating whether a fact is still fresh.
     */
    public record FreshnessContext(
            String currentRevision,
            String currentWorkspaceFingerprint,
            Instant harnessNow,
            Duration maxTtl
    ) {

        public FreshnessContext {
            currentRevision = normalize(currentRevision);
            currentWorkspaceFingerprint = normalize(currentWorkspaceFingerprint);
            harnessNow = harnessNow == null ? Instant.now() : harnessNow;
            maxTtl = maxTtl == null || maxTtl.isNegative() || maxTtl.isZero()
                    ? DEFAULT_MAX_TTL
                    : maxTtl;
        }

        private static String normalize(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.strip();
            return normalized.isBlank() ? null : normalized;
        }
    }

    /**
     * Evaluates freshness without mutating the fact. Stale facts remain in audit artifacts.
     */
    public static FactFreshnessStatus evaluate(RoleExecutionFact fact, FreshnessContext context) {
        Objects.requireNonNull(fact, "fact must not be null");
        FreshnessContext safeContext = context == null ? new FreshnessContext(null, null, Instant.now(), DEFAULT_MAX_TTL)
                : context;
        return switch (fact.freshnessPolicy()) {
            case SAME_REVISION -> revisionMatches(fact, safeContext) ? FactFreshnessStatus.FRESH : FactFreshnessStatus.STALE;
            case SAME_WORKSPACE -> workspaceMatches(fact, safeContext)
                    ? FactFreshnessStatus.FRESH
                    : FactFreshnessStatus.STALE;
            case TTL -> ttlValid(fact, safeContext) ? FactFreshnessStatus.FRESH : FactFreshnessStatus.STALE;
            case ALWAYS_RECHECK -> FactFreshnessStatus.STALE;
        };
    }

    public static boolean isPromptEligible(RoleExecutionFact fact, FreshnessContext context) {
        return fact.kind() == FactKind.OBSERVED
                && evaluate(fact, context) == FactFreshnessStatus.FRESH;
    }

    private static boolean revisionMatches(RoleExecutionFact fact, FreshnessContext context) {
        return fact.repoRevision() != null
                && !fact.repoRevision().isBlank()
                && context.currentRevision() != null
                && fact.repoRevision().equals(context.currentRevision());
    }

    private static boolean workspaceMatches(RoleExecutionFact fact, FreshnessContext context) {
        return fact.workspaceFingerprint() != null
                && !fact.workspaceFingerprint().isBlank()
                && context.currentWorkspaceFingerprint() != null
                && fact.workspaceFingerprint().equals(context.currentWorkspaceFingerprint());
    }

    private static boolean ttlValid(RoleExecutionFact fact, FreshnessContext context) {
        if (fact.expiresAt() == null || fact.expiresAt().isBlank()) {
            return false;
        }
        try {
            Instant expiresAt = Instant.parse(fact.expiresAt());
            if (!expiresAt.isAfter(context.harnessNow())) {
                return false;
            }
            Instant observedAt = fact.observedAt() == null || fact.observedAt().isBlank()
                    ? context.harnessNow()
                    : parseInstant(fact.observedAt());
            Duration ttl = Duration.between(observedAt, expiresAt);
            return !ttl.isNegative() && !ttl.minus(context.maxTtl()).isPositive();
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private static Instant parseInstant(String value) {
        return Instant.parse(value);
    }
}
