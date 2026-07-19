package com.wish.rd.engine.requirement.review.model;

/** One task-scoped, redacted source included in an AI review package. */
public record AiReviewSource(
        String sourceId,
        String sourceType,
        String role,
        String artifactType,
        String contentHash,
        String content,
        String metadataJson,
        long createdAtEpochMillis
) {
    public AiReviewSource {
        sourceId = requireText(sourceId, "sourceId");
        sourceType = requireText(sourceType, "sourceType").toUpperCase();
        role = safe(role).toUpperCase();
        artifactType = safe(artifactType).toUpperCase();
        contentHash = requireText(contentHash, "contentHash");
        content = safe(content);
        metadataJson = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson.strip();
    }

    private static String requireText(String value, String field) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
