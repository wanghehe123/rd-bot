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

    /**
     * 是否已经把请求交给远端，只能靠查询收敛。这类行归 Poller 所有，
     * 永远不得重新走提交路径。
     *
     * @return 需要查询远端时为 true
     */
    public boolean awaitingRemoteOutcome() {
        return this == SUBMITTED
                || this == WAITING_REMOTE
                || this == UNKNOWN_REMOTE_RESULT
                || this == VERIFYING;
    }
}
