package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.mapper.ProjectMemoryPurgeMapper;
import com.wish.rd.rag.project.memory.ProjectMemoryPurgePort;
import com.wish.rd.rag.project.memory.model.ProjectMemoryPurgeCounts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL physical purge adapter for project-scoped memory rows. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresProjectMemoryPurgePort implements ProjectMemoryPurgePort {

    private final ProjectMemoryPurgeMapper mapper;

    public PostgresProjectMemoryPurgePort(ProjectMemoryPurgeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ProjectMemoryPurgeCounts previewCounts(String projectId) {
        return countRows(projectId);
    }

    @Override
    @Transactional
    public ProjectMemoryPurgeCounts executePurge(String projectId) {
        ProjectMemoryPurgeCounts counts = countRows(projectId);
        long normalizedProjectId = strictProject(projectId);
        mapper.deleteLegacyLinks(normalizedProjectId);
        mapper.clearHeads(normalizedProjectId);
        mapper.deleteSources(normalizedProjectId);
        mapper.deleteRevisions(normalizedProjectId);
        mapper.deleteMemories(normalizedProjectId);
        mapper.deleteOperations(normalizedProjectId);
        return counts;
    }

    private ProjectMemoryPurgeCounts countRows(String projectId) {
        long normalizedProjectId = strictProject(projectId);
        return new ProjectMemoryPurgeCounts(
                mapper.countMemories(normalizedProjectId),
                mapper.countRevisions(normalizedProjectId),
                mapper.countSources(normalizedProjectId),
                mapper.countOperations(normalizedProjectId),
                mapper.countLegacyLinks(normalizedProjectId)
        );
    }

    private static long strictProject(String projectId) {
        return PostgresPersistenceSupport.parseId(projectId);
    }
}
