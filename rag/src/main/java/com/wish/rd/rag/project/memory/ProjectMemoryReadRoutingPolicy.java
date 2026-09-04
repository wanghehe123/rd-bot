package com.wish.rd.rag.project.memory;

import com.wish.rd.rag.project.memory.model.ProjectMemoryCaptureMode;
import com.wish.rd.rag.project.memory.model.ProjectMemoryModeConfig;
import com.wish.rd.rag.project.memory.model.ProjectMemoryReadMode;

/** Host-owned capture/read routing derived from persisted per-project modes. */
public final class ProjectMemoryReadRoutingPolicy {

    private ProjectMemoryReadRoutingPolicy() {
    }

    public static boolean projectOperational(boolean enabled, boolean deleted) {
        return enabled && !deleted;
    }

    public static ProjectMemoryModeConfig effectiveConfig(
            ProjectMemoryModeConfig config,
            boolean enabled,
            boolean deleted
    ) {
        if (!projectOperational(enabled, deleted)) {
            return ProjectMemoryModeConfig.defaults(config.projectId());
        }
        return config;
    }

    public static boolean shouldRegisterSkippedMarker(
            ProjectMemoryModeConfig config,
            boolean enabled,
            boolean deleted
    ) {
        return projectOperational(enabled, deleted)
                && effectiveConfig(config, enabled, deleted).captureMode() == ProjectMemoryCaptureMode.OFF;
    }

    public static boolean shouldRunExtractor(
            ProjectMemoryModeConfig config,
            boolean enabled,
            boolean deleted
    ) {
        if (!projectOperational(enabled, deleted)) {
            return false;
        }
        ProjectMemoryCaptureMode captureMode = effectiveConfig(config, enabled, deleted).captureMode();
        return captureMode == ProjectMemoryCaptureMode.SHADOW
                || captureMode == ProjectMemoryCaptureMode.ACTIVE;
    }

    public static boolean shouldInjectProjectMemory(
            ProjectMemoryModeConfig config,
            boolean enabled,
            boolean deleted
    ) {
        if (!projectOperational(enabled, deleted)) {
            return false;
        }
        ProjectMemoryReadMode readMode = effectiveConfig(config, enabled, deleted).readMode();
        return readMode == ProjectMemoryReadMode.DUAL || readMode == ProjectMemoryReadMode.PRIMARY;
    }

    public static boolean shouldShadowAuditProjectMemory(
            ProjectMemoryModeConfig config,
            boolean enabled,
            boolean deleted
    ) {
        if (!projectOperational(enabled, deleted)) {
            return false;
        }
        return effectiveConfig(config, enabled, deleted).readMode() == ProjectMemoryReadMode.SHADOW;
    }

    public static boolean shouldIncludeLegacyExperience(
            ProjectMemoryModeConfig config,
            boolean enabled,
            boolean deleted
    ) {
        if (!projectOperational(enabled, deleted)) {
            return false;
        }
        ProjectMemoryReadMode readMode = effectiveConfig(config, enabled, deleted).readMode();
        return readMode == ProjectMemoryReadMode.LEGACY
                || readMode == ProjectMemoryReadMode.SHADOW
                || readMode == ProjectMemoryReadMode.DUAL;
    }
}
