package com.wish.rd.engine.admin.projectmemory;

/** Execute-time row counts diverged from the authorized preview. */
public final class ProjectMemoryPurgeRowCountMismatchException extends RuntimeException {

    public ProjectMemoryPurgeRowCountMismatchException(String message) {
        super(message == null ? "" : message);
    }
}
