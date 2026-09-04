package com.wish.rd.engine.project.memory.model;

import com.wish.rd.rag.project.memory.model.ProjectMemoryType;

/** Untrusted extractor proposal. Host evidence overwrites its project and source identity. */
public record ProjectMemoryCandidate(
        String projectId,
        String sourceArtifactId,
        String type,
        String logicalKey,
        String title,
        String summary,
        String schemaVersion,
        ProjectMemoryType memoryType
) {
    public ProjectMemoryCandidate(String projectId, String sourceArtifactId, String type, String logicalKey,
                                  String title, String summary, String schemaVersion) {
        this(projectId, sourceArtifactId, type, logicalKey, title, summary, schemaVersion, parse(type));
    }

    public ProjectMemoryCandidate {
        projectId = safe(projectId);
        sourceArtifactId = safe(sourceArtifactId);
        type = safe(type);
        logicalKey = safe(logicalKey);
        title = safe(title);
        summary = safe(summary);
        schemaVersion = safe(schemaVersion);
        memoryType = memoryType == null ? parse(type) : memoryType;
    }

    public ProjectMemoryCandidate withHostIdentity(String hostProjectId, String hostArtifactId, String redactedSummary) {
        return new ProjectMemoryCandidate(hostProjectId, hostArtifactId, memoryType.name(), logicalKey, title,
                redactedSummary, schemaVersion, memoryType);
    }

    private static ProjectMemoryType parse(String raw) {
        try {
            return ProjectMemoryType.valueOf(safe(raw));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported project memory type: " + safe(raw));
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
