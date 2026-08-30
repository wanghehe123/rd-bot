package com.wish.rd.rag.project.memory.impl;

import com.wish.rd.rag.project.memory.ProjectMemoryLegacyLinkStore;
import com.wish.rd.rag.project.memory.model.ProjectMemoryLegacyLink;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Contract implementation for legacy migration links. */
public final class InMemoryProjectMemoryLegacyLinkStore implements ProjectMemoryLegacyLinkStore {
    private final ConcurrentMap<String, ProjectMemoryLegacyLink> links = new ConcurrentHashMap<>();

    @Override
    public Optional<ProjectMemoryLegacyLink> findByLegacyExperienceId(String legacyExperienceId) {
        return Optional.ofNullable(links.get(normalize(legacyExperienceId)));
    }

    @Override
    public List<ProjectMemoryLegacyLink> listByProject(String projectId) {
        String normalized = projectId == null ? "" : projectId.strip();
        return links.values().stream()
                .filter(link -> link.projectId().equals(normalized))
                .toList();
    }

    @Override
    public void save(ProjectMemoryLegacyLink link) {
        Objects.requireNonNull(link, "link must not be null");
        links.put(link.legacyExperienceId(), link);
    }

    private static String normalize(String legacyExperienceId) {
        String normalized = legacyExperienceId == null ? "" : legacyExperienceId.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("legacyExperienceId must not be blank");
        }
        return normalized;
    }
}
