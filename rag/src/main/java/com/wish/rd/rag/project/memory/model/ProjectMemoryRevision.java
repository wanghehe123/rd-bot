package com.wish.rd.rag.project.memory.model;

import java.util.Objects;

/** Immutable content revision. Lifecycle transitions create a different revision or are persisted with CAS. */
public record ProjectMemoryRevision(
        String revisionId,
        String memoryId,
        long version,
        ProjectMemoryRevisionStatus status,
        String title,
        String summary,
        String contentJson,
        String contentHash,
        String schemaVersion,
        String supersedesRevisionId,
        long createdAtEpochMillis,
        long rowVersion
) {
    public ProjectMemoryRevision {
        revisionId = required(revisionId, "revisionId");
        memoryId = required(memoryId, "memoryId");
        if (version <= 0L) {
            throw new IllegalArgumentException("version must be positive");
        }
        status = Objects.requireNonNull(status, "status must not be null");
        title = text(title);
        summary = text(summary);
        contentJson = text(contentJson);
        contentHash = sha256(contentHash);
        schemaVersion = required(schemaVersion, "schemaVersion");
        supersedesRevisionId = text(supersedesRevisionId);
        createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
        rowVersion = rowVersion <= 0L ? 1L : rowVersion;
    }

    private static String sha256(String value) {
        String normalized = text(value);
        if (!normalized.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("contentHash must be a SHA-256 hex digest");
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

    public ProjectMemoryRevision withStatus(ProjectMemoryRevisionStatus next) {
        return new ProjectMemoryRevision(
                revisionId, memoryId, version, next, title, summary, contentJson,
                contentHash, schemaVersion, supersedesRevisionId, createdAtEpochMillis, rowVersion + 1L);
    }

    public ProjectMemoryRevision withContent(String nextTitle, String nextSummary, String nextContentJson, String nextContentHash) {
        return new ProjectMemoryRevision(
                revisionId, memoryId, version, status, nextTitle, nextSummary, nextContentJson,
                nextContentHash, schemaVersion, supersedesRevisionId, createdAtEpochMillis, rowVersion + 1L);
    }
}
