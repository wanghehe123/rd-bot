package com.wish.rd.rag.knowledge.projection.model;

/**
 * 远端操作生命周期。提交事务只产生 {@code PENDING}；claim/settle 是后续独立 CAS。
 */
public enum ExternalKnowledgeOperationStatus {
    PENDING,
    CLAIMED,
    UPLOADING,
    SUBMITTED,
    WAITING_REMOTE,
    VERIFYING,
    SUCCEEDED,
    UNKNOWN_REMOTE_RESULT,
    RETRY_WAIT,
    SUPERSEDED,
    DEAD_LETTER,
    NEEDS_HUMAN;

    public boolean terminal() {
        return this == SUCCEEDED || this == SUPERSEDED || this == DEAD_LETTER;
    }

    public boolean claimable() {
        return this == PENDING || this == RETRY_WAIT || this == CLAIMED;
    }
}
