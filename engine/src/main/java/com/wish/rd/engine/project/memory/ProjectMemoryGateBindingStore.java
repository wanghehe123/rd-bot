package com.wish.rd.engine.project.memory;

import com.wish.rd.engine.project.memory.model.ProjectMemoryGateBinding;

import java.util.Optional;

/** Stores per-project PRIMARY gate bindings. */
public interface ProjectMemoryGateBindingStore {
    Optional<ProjectMemoryGateBinding> find(String projectId);

    void save(ProjectMemoryGateBinding binding);

    void clear(String projectId);
}
