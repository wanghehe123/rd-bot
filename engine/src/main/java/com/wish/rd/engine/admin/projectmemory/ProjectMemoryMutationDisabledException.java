package com.wish.rd.engine.admin.projectmemory;

/** Raised when governance mutations are disabled because no trusted principal provider exists. */
public final class ProjectMemoryMutationDisabledException extends RuntimeException {

    public ProjectMemoryMutationDisabledException(String message) {
        super(message == null ? "" : message);
    }
}
