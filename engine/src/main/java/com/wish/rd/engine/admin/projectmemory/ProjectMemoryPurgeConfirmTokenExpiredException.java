package com.wish.rd.engine.admin.projectmemory;

/** Confirm token TTL elapsed before execute completed re-authorization. */
public final class ProjectMemoryPurgeConfirmTokenExpiredException extends RuntimeException {

    public ProjectMemoryPurgeConfirmTokenExpiredException(String message) {
        super(message == null ? "" : message);
    }
}
