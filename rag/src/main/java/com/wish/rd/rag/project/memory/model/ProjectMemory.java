package com.wish.rd.rag.project.memory.model;

import java.util.Objects;

/**
 * Stable logical project-owned memory identity and its current head pointer.
 *
 * <p>The pointer is advanced only by a store-level compare-and-swap. A replacement head is legal
 * when the caller supplies the current row version; lifecycle transition of the displaced revision
 * is persisted atomically by the production consolidation transaction.
 */
public record ProjectMemory(
        String memoryId,
        String projectId,
        String scopeRole,
        ProjectMemoryType memoryType,
        String logicalKey,
        long rowVersion,
        String headRevisionId,
        long headVersion,
        boolean deleted
) {

    public ProjectMemory(String memoryId, String projectId, String scopeRole, ProjectMemoryType memoryType,
                         String logicalKey, long rowVersion) {
        this(memoryId, projectId, scopeRole, memoryType, logicalKey, rowVersion, "", 0L, false);
    }

    public ProjectMemory {
        memoryId = required(memoryId, "memoryId");
        projectId = numericProjectId(projectId);
        scopeRole = text(scopeRole);
        memoryType = Objects.requireNonNull(memoryType, "memoryType must not be null");
        logicalKey = required(logicalKey, "logicalKey");
        rowVersion = rowVersion <= 0L ? 1L : rowVersion;
        headRevisionId = text(headRevisionId);
        headVersion = Math.max(0L, headVersion);
    }

    public ProjectMemory withHead(String revisionId, long version) {
        return new ProjectMemory(
                memoryId, projectId, scopeRole, memoryType, logicalKey, rowVersion + 1L,
                required(revisionId, "headRevisionId"), version, deleted
        );
    }

    public ProjectMemory withDeleted() {
        return new ProjectMemory(
                memoryId, projectId, scopeRole, memoryType, logicalKey, rowVersion + 1L,
                headRevisionId, headVersion, true
        );
    }

    private static String numericProjectId(String value) {
        String normalized = text(value);
        if (!normalized.matches("[0-9]+") || normalized.equals("0")) {
            throw new IllegalArgumentException("projectId must be a non-empty numeric identifier");
        }
        return normalized;
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
