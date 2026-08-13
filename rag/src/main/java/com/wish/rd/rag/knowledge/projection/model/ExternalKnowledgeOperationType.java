package com.wish.rd.rag.knowledge.projection.model;

/**
 * Outbox 操作类型。Worker 实现属于后续 WP。
 */
public enum ExternalKnowledgeOperationType {
    UPSERT_DOCUMENT,
    DELETE_DOCUMENT,
    DELETE_KNOWLEDGE_BASE,
    REBUILD_DOCUMENT,
    VERIFY_ONLY
}
