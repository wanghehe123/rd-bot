package com.wish.rd.engine.requirement.review.model;

import java.util.List;

/** One scored AI review dimension with references into the immutable input manifest. */
public record AiReviewDimension(
        String name,
        int score,
        String reason,
        List<String> sourceIds
) {
    public AiReviewDimension {
        name = requireText(name, "name");
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("dimension score must be between 0 and 100");
        }
        reason = requireText(reason, "reason");
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
