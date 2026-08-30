package com.wish.rd.engine.project.memory;

import com.wish.rd.rag.project.memory.model.ProjectMemoryOperationClaim;

/** Processes one leased consolidation operation. */
@FunctionalInterface
public interface ProjectMemoryOperationHandler {
    String handle(ProjectMemoryOperationClaim claim) throws ProjectMemoryOperationRetryableException;
}
