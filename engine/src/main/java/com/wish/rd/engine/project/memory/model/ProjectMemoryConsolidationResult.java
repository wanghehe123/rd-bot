package com.wish.rd.engine.project.memory.model;

import com.wish.rd.engine.project.memory.ProjectMemoryResolver;

/** Outcome of one consolidation attempt against the durable memory store. */
public record ProjectMemoryConsolidationResult(
        ProjectMemoryResolver.Action action,
        String memoryId,
        String revisionId,
        String sourceId,
        boolean replayedExistingRevision
) {
}
