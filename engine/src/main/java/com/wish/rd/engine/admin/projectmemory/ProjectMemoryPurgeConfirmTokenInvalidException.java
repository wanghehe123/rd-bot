package com.wish.rd.engine.admin.projectmemory;

/** Confirm token is missing, reused, or does not match preview context. */
public final class ProjectMemoryPurgeConfirmTokenInvalidException extends RuntimeException {

    public ProjectMemoryPurgeConfirmTokenInvalidException(String message) {
        super(message == null ? "" : message);
    }
}
