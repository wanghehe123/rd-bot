package com.wish.rd.engine.requirement.review.model;

/** Redacted metadata and preview for one AI review input or output artifact. */
public record AiReviewArtifact(
        String artifactId,
        String runId,
        String artifactType,
        String artifactUri,
        String contentPreview,
        String contentHash,
        String metadataJson,
        boolean redacted,
        long createdAtEpochMillis
) {
    public AiReviewArtifact {
        artifactId = requireText(artifactId, "artifactId");
        runId = requireText(runId, "runId");
        artifactType = requireText(artifactType, "artifactType");
        artifactUri = safe(artifactUri);
        contentPreview = safe(contentPreview);
        contentHash = safe(contentHash);
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
