package com.wish.rd.rag.retrieval.run.model;

/** State of one auditable operation inside a retrieval iteration. */
public enum RetrievalStepStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    SKIPPED,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
    CANCELLED;

    public boolean isTerminal() {
        return this != PENDING && this != RUNNING;
    }
}
