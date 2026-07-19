package com.wish.rd.rag.retrieval.run.model;

/**
 * Redacted, inspectable evidence or process projection for one retrieval attempt.
 *
 * <p>The control plane stores bounded previews only; callers must never use this type to persist
 * raw prompts, full documents, or unredacted provider payloads.
 */
public record RetrievalRunArtifact(
        String artifactId,
        String runId,
        String artifactType,
        String artifactUri,
        String contentPreview,
        String contentHash,
        boolean redacted,
        long createdAtEpochMillis
) {

    public RetrievalRunArtifact {
        artifactId = safe(artifactId);
        runId = safe(runId);
        artifactType = safe(artifactType).toUpperCase();
        artifactUri = safe(artifactUri);
        contentPreview = safe(contentPreview);
        contentHash = safe(contentHash);
        createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
