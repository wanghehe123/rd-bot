package com.wish.rd.engine.requirement.review.model;

import java.util.List;

/** One actionable AI review finding backed by package source IDs. */
public record AiReviewFinding(
        AiReviewSeverity severity,
        String title,
        String detail,
        List<String> sourceIds,
        String suggestion
) {
    public AiReviewFinding {
        if (severity == null) {
            throw new IllegalArgumentException("severity must not be null");
        }
        title = requireText(title, "title");
        detail = requireText(detail, "detail");
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        suggestion = requireText(suggestion, "suggestion");
    }

    public boolean isBlocking() {
        return severity == AiReviewSeverity.HIGH || severity == AiReviewSeverity.CRITICAL;
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
