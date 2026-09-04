package com.wish.rd.rag.project.memory.model;

/** Persisted migration link from a legacy experience row to an optional candidate revision. */
public record ProjectMemoryLegacyLink(
        String legacyExperienceId,
        String projectId,
        String revisionId,
        LegacyExperienceInventoryDecision decision,
        String reason
) {
    public ProjectMemoryLegacyLink {
        legacyExperienceId = required(legacyExperienceId, "legacyExperienceId");
        projectId = numericProjectId(projectId);
        revisionId = revisionId == null ? "" : revisionId.strip();
        decision = decision == null ? LegacyExperienceInventoryDecision.REJECTED : decision;
        reason = reason == null ? "" : reason.strip();
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String numericProjectId(String value) {
        String normalized = value == null ? "" : value.strip();
        if (!normalized.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("projectId must be a non-empty numeric identifier");
        }
        return normalized;
    }
}
