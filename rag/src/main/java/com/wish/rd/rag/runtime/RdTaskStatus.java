package com.wish.rd.rag.runtime;

/**
 * RD 任务状态机节点。
 *
 * <p>供 {@link RagStreamTaskRegistry}、任务查询接口和持久化适配器共同使用。
 * {@code DELETED} 不是状态机流转节点，仅供管理台逻辑删除标记使用，不参与
 * {@code ensureTransition} 合法性校验。
 */
public enum RdTaskStatus {
    CREATED,
    MATERIAL_COLLECTING,
    MATERIAL_READY,
    CONTEXT_BUILDING,
    CONTEXT_READY,
    PLAN_GENERATING,
    PLAN_GENERATED,
    WAITING_POLICY,
    WAITING_APPROVAL,
    SEARCHING,
    EXECUTING,
    VALIDATING,
    PR_CREATING,
    COMMITTED,
    MERGED,
    REPORTING,
    COMPLETED,
    REJECTED,
    FAILED_RETRYABLE,
    FAILED_NEEDS_HUMAN,
    CANCELLED,
    DEAD_LETTERED,
    RECOVERING,
    /** 管理台逻辑删除标记，不参与状态机流转。 */
    DELETED
}
