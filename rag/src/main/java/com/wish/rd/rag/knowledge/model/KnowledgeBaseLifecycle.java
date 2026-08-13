package com.wish.rd.rag.knowledge.model;

/**
 * 知识库生命周期。普通列表只展示 {@link #ACTIVE}。
 */
public enum KnowledgeBaseLifecycle {
    ACTIVE,
    DELETING,
    DELETED
}
