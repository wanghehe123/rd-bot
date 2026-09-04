package com.wish.rd.engine.project.memory;

/** Host-owned provenance used to validate an extractor proposal. */
public record ProjectMemoryCaptureEvidence(
        String projectId,
        String sourceArtifactId,
        String sourceContentHash,
        String extractorVersion
) {
    public ProjectMemoryCaptureEvidence {
        projectId = safe(projectId);
        sourceArtifactId = safe(sourceArtifactId);
        sourceContentHash = safe(sourceContentHash).toLowerCase(java.util.Locale.ROOT);
        extractorVersion = safe(extractorVersion);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
