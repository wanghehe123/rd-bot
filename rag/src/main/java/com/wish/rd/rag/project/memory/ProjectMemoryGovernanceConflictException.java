package com.wish.rd.rag.project.memory;

/** Optimistic concurrency failure for governance mutations. */
public final class ProjectMemoryGovernanceConflictException extends IllegalStateException {

    public ProjectMemoryGovernanceConflictException(String message) {
        super(message == null ? "" : message);
    }
}
