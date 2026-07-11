package com.wish.rd.engine.draft.model;

import java.util.List;

/** Validated AI draft. No method in this model creates or submits a task. */
public record TaskDraftResult(
        boolean available,
        String reason,
        String actualBehavior,
        String expectedBehavior,
        String reproductionSteps,
        String affectedScope,
        String requirementBody,
        String expectedResult,
        List<String> acceptanceCriteria,
        List<String> missingFields,
        List<String> evidence,
        double confidence,
        boolean aiGenerated
) {
    public TaskDraftResult {
        reason = safe(reason);
        actualBehavior = safe(actualBehavior);
        expectedBehavior = safe(expectedBehavior);
        reproductionSteps = safe(reproductionSteps);
        affectedScope = safe(affectedScope);
        requirementBody = safe(requirementBody);
        expectedResult = safe(expectedResult);
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        confidence = Math.max(0, Math.min(1, confidence));
    }

    private static String safe(String value) { return value == null ? "" : value.strip(); }
}
