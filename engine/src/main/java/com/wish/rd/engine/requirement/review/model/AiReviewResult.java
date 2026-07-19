package com.wish.rd.engine.requirement.review.model;

import com.wish.rd.engine.agent.model.AgentRole;

import java.util.List;

/** Validated, evidence-backed decision produced by one AI review attempt. */
public record AiReviewResult(
        AiReviewDecision decision,
        int score,
        String summary,
        AgentRole retryFromRole,
        List<AiReviewDimension> dimensions,
        List<AiReviewFinding> findings,
        String rawJson
) {
    public AiReviewResult {
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be between 0 and 100");
        }
        summary = summary == null ? "" : summary.strip();
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        findings = findings == null ? List.of() : List.copyOf(findings);
        rawJson = rawJson == null ? "{}" : rawJson.strip();
    }
}
