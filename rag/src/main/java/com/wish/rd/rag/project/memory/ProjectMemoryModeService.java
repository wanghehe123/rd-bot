package com.wish.rd.rag.project.memory;

import com.wish.rd.rag.project.memory.model.ProjectMemoryCaptureMode;
import com.wish.rd.rag.project.memory.model.ProjectMemoryMode;
import com.wish.rd.rag.project.memory.model.ProjectMemoryModeConfig;
import com.wish.rd.rag.project.memory.model.ProjectMemoryReadMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.function.Consumer;

/** Management and routing facade for per-project capture/read policy. */
@Service
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class ProjectMemoryModeService {

    private final ProjectMemoryModeStore store;
    private final Consumer<String> projectValidator;

    @Autowired
    public ProjectMemoryModeService(ProjectMemoryModeStore store) {
        this(store, ignored -> { });
    }

    ProjectMemoryModeService(ProjectMemoryModeStore store, Consumer<String> projectValidator) {
        this.store = Objects.requireNonNull(store, "mode store must not be null");
        this.projectValidator = projectValidator == null ? ignored -> { } : projectValidator;
    }

    public ProjectMemoryModeConfig get(String projectId) {
        String safeProjectId = requireProjectId(projectId);
        projectValidator.accept(safeProjectId);
        return store.getConfig(safeProjectId);
    }

    public ProjectMemoryModeConfig updatePolicy(String projectId, ProjectMemoryMode policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        return saveConfig(ProjectMemoryModeConfig.fromPolicy(requireProjectId(projectId), policy));
    }

    public ProjectMemoryModeConfig updateModes(
            String projectId,
            ProjectMemoryCaptureMode captureMode,
            ProjectMemoryReadMode readMode
    ) {
        ProjectMemoryModeConfig existing = get(projectId);
        long now = System.currentTimeMillis();
        return saveConfig(new ProjectMemoryModeConfig(
                existing.projectId(),
                captureMode,
                readMode,
                existing.createTimeEpochMillis() > 0L ? existing.createTimeEpochMillis() : now,
                now));
    }

    public boolean shouldRunExtractor(String projectId, boolean enabled, boolean deleted) {
        return ProjectMemoryReadRoutingPolicy.shouldRunExtractor(get(projectId), enabled, deleted);
    }

    public boolean shouldInjectProjectMemory(String projectId, boolean enabled, boolean deleted) {
        return ProjectMemoryReadRoutingPolicy.shouldInjectProjectMemory(get(projectId), enabled, deleted);
    }

    public boolean shouldIncludeLegacyExperience(String projectId, boolean enabled, boolean deleted) {
        return ProjectMemoryReadRoutingPolicy.shouldIncludeLegacyExperience(get(projectId), enabled, deleted);
    }

    public boolean shouldShadowAuditProjectMemory(String projectId, boolean enabled, boolean deleted) {
        return ProjectMemoryReadRoutingPolicy.shouldShadowAuditProjectMemory(get(projectId), enabled, deleted);
    }

    private ProjectMemoryModeConfig saveConfig(ProjectMemoryModeConfig config) {
        projectValidator.accept(config.projectId());
        store.saveConfig(config);
        return store.getConfig(config.projectId());
    }

    private static String requireProjectId(String projectId) {
        String normalized = projectId == null ? "" : projectId.strip();
        if (!normalized.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("projectId must be a non-empty numeric identifier");
        }
        return normalized;
    }
}
