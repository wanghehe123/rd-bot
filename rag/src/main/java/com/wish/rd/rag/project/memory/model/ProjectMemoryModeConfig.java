package com.wish.rd.rag.project.memory.model;

import java.util.Objects;
import java.util.Optional;

/** Independently persisted capture/read policy for one project. */
public record ProjectMemoryModeConfig(
        String projectId,
        ProjectMemoryCaptureMode captureMode,
        ProjectMemoryReadMode readMode,
        long createTimeEpochMillis,
        long updateTimeEpochMillis
) {

    public ProjectMemoryModeConfig {
        projectId = requireProjectId(projectId);
        captureMode = captureMode == null ? ProjectMemoryCaptureMode.OFF : captureMode;
        readMode = readMode == null ? ProjectMemoryReadMode.LEGACY : readMode;
    }

    public static ProjectMemoryModeConfig defaults(String projectId) {
        return new ProjectMemoryModeConfig(
                projectId,
                ProjectMemoryCaptureMode.OFF,
                ProjectMemoryReadMode.LEGACY,
                0L,
                0L);
    }

    public static ProjectMemoryModeConfig fromPolicy(String projectId, ProjectMemoryMode policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        long now = System.currentTimeMillis();
        return new ProjectMemoryModeConfig(projectId, policy.captureMode(), policy.readMode(), now, now);
    }

    public Optional<ProjectMemoryMode> namedPolicy() {
        return ProjectMemoryMode.fromModes(captureMode, readMode);
    }

    private static String requireProjectId(String projectId) {
        String normalized = projectId == null ? "" : projectId.strip();
        if (!normalized.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("projectId must be a non-empty numeric identifier");
        }
        return normalized;
    }
}
