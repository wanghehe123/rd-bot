package com.wish.rd.rag.project.memory.model;

/** Bounded, redacted source snapshot that remains auditable after an origin reference is deleted. */
public record ProjectMemorySource(
        String sourceId,
        String revisionId,
        String projectId,
        String taskId,
        String stageRunId,
        String artifactId,
        String sourceUri,
        String sourceContentHash,
        String repositoryRevision,
        String extractorVersion,
        String schemaVersion,
        String redactedSummary
) {
    public ProjectMemorySource {
        sourceId = required(sourceId, "sourceId");
        revisionId = required(revisionId, "revisionId");
        projectId = required(projectId, "projectId");
        taskId = text(taskId);
        stageRunId = text(stageRunId);
        artifactId = text(artifactId);
        sourceUri = required(sourceUri, "sourceUri");
        sourceContentHash = sha256(sourceContentHash);
        repositoryRevision = text(repositoryRevision);
        extractorVersion = required(extractorVersion, "extractorVersion");
        schemaVersion = required(schemaVersion, "schemaVersion");
        redactedSummary = text(redactedSummary);
    }

    public ProjectMemorySource referencesUnavailable() {
        return new ProjectMemorySource(sourceId, revisionId, projectId, "", "", "", sourceUri, sourceContentHash,
                repositoryRevision, extractorVersion, schemaVersion, redactedSummary);
    }

    private static String sha256(String value) {
        String normalized = text(value);
        if (!normalized.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("sourceContentHash must be a SHA-256 hex digest");
        }
        return normalized.toLowerCase(java.util.Locale.ROOT);
    }

    private static String required(String value, String field) {
        String normalized = text(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
