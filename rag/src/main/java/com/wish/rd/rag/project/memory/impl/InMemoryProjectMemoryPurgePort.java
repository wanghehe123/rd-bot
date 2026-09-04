package com.wish.rd.rag.project.memory.impl;

import com.wish.rd.rag.project.memory.ProjectMemoryPurgePort;
import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryPurgeCounts;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevision;
import com.wish.rd.rag.project.memory.model.ProjectMemorySource;

import java.util.ArrayList;
import java.util.List;

/** Contract-only purge adapter backed by {@link InMemoryProjectMemoryStore}. */
public final class InMemoryProjectMemoryPurgePort implements ProjectMemoryPurgePort {

    private final InMemoryProjectMemoryStore store;
    private final InMemoryProjectMemoryAdminQueryPort queryPort;

    public InMemoryProjectMemoryPurgePort(
            InMemoryProjectMemoryStore store,
            InMemoryProjectMemoryAdminQueryPort queryPort
    ) {
        this.store = store;
        this.queryPort = queryPort;
    }

    @Override
    public ProjectMemoryPurgeCounts previewCounts(String projectId) {
        return countProject(projectId);
    }

    @Override
    public ProjectMemoryPurgeCounts executePurge(String projectId) {
        ProjectMemoryPurgeCounts counts = countProject(projectId);
        List<String> memoryIds = store.listByProject(projectId).stream()
                .map(ProjectMemory::memoryId)
                .toList();
        queryPort.purgeRetrievalAudits(memoryIds);
        store.physicalPurgeProject(projectId);
        return counts;
    }

    private ProjectMemoryPurgeCounts countProject(String projectId) {
        List<ProjectMemory> memories = store.listByProject(projectId);
        int memoryCount = memories.size();
        int revisionCount = 0;
        int sourceCount = 0;
        for (ProjectMemory memory : memories) {
            List<ProjectMemoryRevision> revisions = store.listRevisions(memory.memoryId());
            revisionCount += revisions.size();
            for (ProjectMemoryRevision revision : revisions) {
                sourceCount += store.listSources(revision.revisionId()).size();
            }
        }
        return new ProjectMemoryPurgeCounts(memoryCount, revisionCount, sourceCount, 0, 0);
    }
}
