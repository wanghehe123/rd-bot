package com.wish.rd.rag.knowledge.projection.model;

/**
 * 远端后台任务状态。{@code NOT_FOUND} 不等于失败：WP-0 观测到任务 404 只说明
 * 任务列表里查不到，保留期内不得据此判定远端写入失败。
 */
public enum ExternalKnowledgeTaskState {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    NOT_FOUND,
    UNKNOWN
}
