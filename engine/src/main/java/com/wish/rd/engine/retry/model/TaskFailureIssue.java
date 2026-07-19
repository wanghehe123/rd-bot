package com.wish.rd.engine.retry.model;

/** One bounded, operator-actionable item extracted from a failed delivery result. */
public record TaskFailureIssue(
        String kind,
        String severity,
        String title,
        String detail,
        String sourceField
) {

    public TaskFailureIssue {
        kind = safe(kind);
        severity = safe(severity);
        title = safe(title);
        detail = safe(detail);
        sourceField = safe(sourceField);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
