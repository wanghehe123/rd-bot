package com.wish.rd.engine.retry.model;

import java.util.List;

/** Stable human-readable projection of one retryable delivery failure. */
public record TaskFailureDiagnostic(
        String category,
        String title,
        String summary,
        String suggestedAction,
        boolean requiresSupplement,
        List<TaskFailureIssue> issues,
        List<TaskFailureIssue> risks,
        List<TaskFailureIssue> acceptanceGaps
) {

    public TaskFailureDiagnostic {
        category = safe(category);
        title = safe(title);
        summary = safe(summary);
        suggestedAction = safe(suggestedAction);
        issues = List.copyOf(issues == null ? List.of() : issues);
        risks = List.copyOf(risks == null ? List.of() : risks);
        acceptanceGaps = List.copyOf(acceptanceGaps == null ? List.of() : acceptanceGaps);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
