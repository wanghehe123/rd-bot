package com.wish.rd.engine.project.memory;

/** Signals a transient consolidation failure that should backoff and retry. */
public final class ProjectMemoryOperationRetryableException extends Exception {
    public ProjectMemoryOperationRetryableException(String message) {
        super(message);
    }
}
