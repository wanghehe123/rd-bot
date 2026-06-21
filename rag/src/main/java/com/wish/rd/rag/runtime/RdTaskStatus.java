package com.wish.rd.rag.runtime;

/**
 * RD 任务状态机节点。
 *
 * <p>供 {@link RagStreamTaskRegistry}、任务查询接口和持久化适配器共同使用。
 */
public enum RdTaskStatus {
    CREATED,
    SEARCHING,
    EXECUTING,
    COMMITTED,
    MERGED,
    REJECTED
}
