package com.wish.rd.rag.project.runtime.impl;

import com.wish.rd.rag.project.runtime.ProjectRuntimeProfileStore;
import com.wish.rd.rag.project.runtime.model.ProjectRuntimeProfile;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory runtime profile storage used only by local and focused test configurations. */
public final class InMemoryProjectRuntimeProfileStore implements ProjectRuntimeProfileStore {

    private final ConcurrentMap<String, ProjectRuntimeProfile> values = new ConcurrentHashMap<>();

    @Override
    public ProjectRuntimeProfile save(ProjectRuntimeProfile profile) {
        values.put(key(profile.projectId(), profile.role()), profile);
        return profile;
    }

    @Override
    public Optional<ProjectRuntimeProfile> find(String projectId, String role) {
        return Optional.ofNullable(values.get(key(projectId, role)));
    }

    @Override
    public List<ProjectRuntimeProfile> list(String projectId) {
        String prefix = normalized(projectId) + ":";
        return values.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(java.util.Map.Entry::getValue)
                .sorted(Comparator.comparing(ProjectRuntimeProfile::role))
                .toList();
    }

    @Override
    public boolean delete(String projectId, String role) {
        return values.remove(key(projectId, role)) != null;
    }

    private static String key(String projectId, String role) {
        return normalized(projectId) + ":" + normalized(role).toUpperCase(java.util.Locale.ROOT);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip();
    }
}
