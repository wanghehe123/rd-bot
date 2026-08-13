package com.wish.rd.rag.knowledge.projection.model;

/**
 * 给管理面看的派生快照，由 desired/operation/observed 计算，不单独当真值。
 */
public enum ExternalKnowledgeProjectionStatus {
    PENDING,
    PROCESSING,
    IN_SYNC,
    DELETING,
    DELETED,
    DRIFTED,
    FAILED,
    DEAD_LETTER,
    NEEDS_HUMAN
}
