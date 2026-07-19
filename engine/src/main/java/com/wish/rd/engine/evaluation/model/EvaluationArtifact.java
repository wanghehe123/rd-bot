package com.wish.rd.engine.evaluation.model;

/** Metadata for a local evaluation output; paths are always repository-relative URIs. */
public record EvaluationArtifact(
        String artifactId,
        String artifactType,
        String artifactUri,
        String contentPreview,
        String contentHash,
        long sizeBytes,
        long createdAtEpochMillis
) {
}
