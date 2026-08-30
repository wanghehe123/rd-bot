package com.wish.rd.engine.admin.projectmemory.model;

/** Redacted source snapshot for admin detail views. */
public record ProjectMemoryAdminSourceView(
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
        String redactedSummary,
        boolean originReferencesAvailable
) {}
