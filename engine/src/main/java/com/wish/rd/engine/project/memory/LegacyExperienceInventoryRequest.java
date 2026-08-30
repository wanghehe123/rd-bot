package com.wish.rd.engine.project.memory;

import com.wish.rd.engine.agent.model.WorkflowExperienceEntry;

import java.util.List;
import java.util.Objects;

/** Host-owned inventory request for one project batch. */
public record LegacyExperienceInventoryRequest(
        String projectId,
        String expectedRepositoryFingerprint,
        List<WorkflowExperienceEntry> experiences
) {
    public LegacyExperienceInventoryRequest {
        projectId = requireProjectId(projectId);
        expectedRepositoryFingerprint = expectedRepositoryFingerprint == null
                ? "" : expectedRepositoryFingerprint.strip().toLowerCase(java.util.Locale.ROOT);
        experiences = experiences == null ? List.of() : List.copyOf(experiences);
    }

    private static String requireProjectId(String projectId) {
        String normalized = projectId == null ? "" : projectId.strip();
        if (!normalized.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("projectId must be a non-empty numeric identifier");
        }
        return normalized;
    }

    public static LegacyExperienceInventoryRequest of(
            String projectId,
            String expectedRepositoryFingerprint,
            WorkflowExperienceEntry... experiences
    ) {
        Objects.requireNonNull(experiences, "experiences must not be null");
        return new LegacyExperienceInventoryRequest(projectId, expectedRepositoryFingerprint, List.of(experiences));
    }
}
