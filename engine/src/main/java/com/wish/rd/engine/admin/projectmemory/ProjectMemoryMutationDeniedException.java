package com.wish.rd.engine.admin.projectmemory;

/** Authorization failure for governance mutations. */
public final class ProjectMemoryMutationDeniedException extends RuntimeException {

    public ProjectMemoryMutationDeniedException(String message) {
        super(message == null ? "" : message);
    }
}
