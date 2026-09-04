package com.wish.rd.engine.project.memory;

import com.wish.rd.engine.project.memory.model.ProjectMemoryGateBinding;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory gate binding store for contract tests. */
public final class InMemoryProjectMemoryGateBindingStore implements ProjectMemoryGateBindingStore {
    private final ConcurrentMap<String, ProjectMemoryGateBinding> bindings = new ConcurrentHashMap<>();

    @Override
    public Optional<ProjectMemoryGateBinding> find(String projectId) {
        return Optional.ofNullable(bindings.get(normalize(projectId)));
    }

    @Override
    public void save(ProjectMemoryGateBinding binding) {
        Objects.requireNonNull(binding, "binding must not be null");
        bindings.put(binding.projectId(), binding);
    }

    @Override
    public void clear(String projectId) {
        bindings.remove(normalize(projectId));
    }

    private static String normalize(String projectId) {
        String normalized = projectId == null ? "" : projectId.strip();
        if (!normalized.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("projectId must be a non-empty numeric identifier");
        }
        return normalized;
    }
}
