package com.wish.rd.rag.knowledge.projection.model;

/**
 * 回填事务提交结果。CAS 失败与已有绑定必须分开，不能都收成 false。
 */
public enum KnowledgeProjectionBackfillCommitResult {
    APPLIED,
    ALREADY_BOUND,
    CONCURRENT_MODIFICATION
}
