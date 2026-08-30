package com.wish.rd.rag.project.memory.model;

/** Durable consolidation operation lifecycle. */
public enum ProjectMemoryOperationStatus {
    PENDING,
    RUNNING,
    RETRYABLE,
    SUCCEEDED,
    FAILED,
    NEEDS_HUMAN;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == NEEDS_HUMAN;
    }
}
