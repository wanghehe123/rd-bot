package com.wish.rd.rag.project.memory;

import com.wish.rd.rag.project.memory.model.ProjectMemoryLegacyLink;

import java.util.List;
import java.util.Optional;

/** Port for legacy experience migration links. */
public interface ProjectMemoryLegacyLinkStore {
    Optional<ProjectMemoryLegacyLink> findByLegacyExperienceId(String legacyExperienceId);

    List<ProjectMemoryLegacyLink> listByProject(String projectId);

    void save(ProjectMemoryLegacyLink link);
}
