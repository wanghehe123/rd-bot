package com.wish.rd.engine.admin.projectmemory.model;

import com.wish.rd.rag.project.memory.model.ProjectMemoryPurgeCounts;

/** Completed physical purge after preview confirmation and re-authorization. */
public record ProjectMemoryPurgeExecuteResult(
        String projectId,
        String operatorId,
        String reason,
        ProjectMemoryPurgeCounts expectedCounts,
        ProjectMemoryPurgeCounts actualCounts,
        String requestId
) {}
