package com.wish.rd.rag.project.memory.impl;

import com.wish.rd.rag.project.memory.ProjectMemoryModeStore;
import com.wish.rd.rag.project.memory.model.ProjectMemoryModeConfig;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Contract implementation; disabled/deleted projects fail closed irrespective of retained data. */
public final class InMemoryProjectMemoryModeStore implements ProjectMemoryModeStore {
    private final ConcurrentMap<String, ProjectMemoryModeConfig> modes = new ConcurrentHashMap<>();

    @Override
    public ProjectMemoryModeConfig getConfig(String projectId) {
        String normalized = projectId(projectId);
        return modes.getOrDefault(normalized, ProjectMemoryModeConfig.defaults(normalized));
    }

    @Override
    public void saveConfig(ProjectMemoryModeConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        modes.put(projectId(config.projectId()), config);
    }

    private static String projectId(String value) {
        return ProjectMemoryModeConfig.defaults(value).projectId();
    }
}
