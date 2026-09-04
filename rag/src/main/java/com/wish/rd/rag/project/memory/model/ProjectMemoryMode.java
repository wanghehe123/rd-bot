package com.wish.rd.rag.project.memory.model;

import java.util.Optional;

/** Named policy mapping retained for operator-safe per-project rollout and rollback. */
public enum ProjectMemoryMode {
    OFF(ProjectMemoryCaptureMode.OFF, ProjectMemoryReadMode.LEGACY),
    SHADOW(ProjectMemoryCaptureMode.SHADOW, ProjectMemoryReadMode.SHADOW),
    DUAL_READ(ProjectMemoryCaptureMode.ACTIVE, ProjectMemoryReadMode.DUAL),
    PRIMARY(ProjectMemoryCaptureMode.ACTIVE, ProjectMemoryReadMode.PRIMARY);

    private final ProjectMemoryCaptureMode captureMode;
    private final ProjectMemoryReadMode readMode;

    ProjectMemoryMode(ProjectMemoryCaptureMode captureMode, ProjectMemoryReadMode readMode) {
        this.captureMode = captureMode;
        this.readMode = readMode;
    }

    public ProjectMemoryCaptureMode captureMode() { return captureMode; }
    public ProjectMemoryReadMode readMode() { return readMode; }

    public static Optional<ProjectMemoryMode> fromModes(
            ProjectMemoryCaptureMode captureMode,
            ProjectMemoryReadMode readMode
    ) {
        for (ProjectMemoryMode mode : values()) {
            if (mode.captureMode == captureMode && mode.readMode == readMode) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
