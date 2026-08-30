package com.wish.rd.rag.project.memory;

import com.wish.rd.rag.project.memory.model.ProjectMemoryPurgeCounts;

/** Host-owned physical purge of all project-scoped memory rows. */
public interface ProjectMemoryPurgePort {

    ProjectMemoryPurgeCounts previewCounts(String projectId);

    ProjectMemoryPurgeCounts executePurge(String projectId);

    record ProjectMemoryPurgeCommand(String projectId, String reason, int expectedTotalRowCount) {}
}
