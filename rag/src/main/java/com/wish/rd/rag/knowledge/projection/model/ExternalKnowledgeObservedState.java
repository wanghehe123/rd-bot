package com.wish.rd.rag.knowledge.projection.model;

/**
 * 最近一次验证到的远端观测，不写入 {@code KnowledgeDocumentStatus}。
 */
public enum ExternalKnowledgeObservedState {
    UNKNOWN,
    ABSENT,
    PRESENT_UNVERIFIED,
    READY,
    DRIFTED,
    FOREIGN
}
