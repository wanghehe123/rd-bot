package com.wish.rd.rag.project.memory;

import com.wish.rd.rag.project.memory.model.ProjectMemoryMode;
import com.wish.rd.rag.project.memory.model.ProjectMemoryModeConfig;

/** Port for the independently persisted capture/read policy. */
public interface ProjectMemoryModeStore {
    ProjectMemoryModeConfig getConfig(String projectId);

    void saveConfig(ProjectMemoryModeConfig config);

    default ProjectMemoryMode find(String projectId) {
        return getConfig(projectId).namedPolicy().orElse(ProjectMemoryMode.OFF);
    }

    default void save(String projectId, ProjectMemoryMode mode) {
        saveConfig(ProjectMemoryModeConfig.fromPolicy(projectId, mode));
    }
}
