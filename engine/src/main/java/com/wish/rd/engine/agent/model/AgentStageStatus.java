package com.wish.rd.engine.agent.model;

/**
 * 多 Agent 阶段运行状态。
 *
 * <p>主任务仍使用 {@code RdTaskStatus} 表达对外状态，阶段状态只描述单个角色运行进度。
 */
public enum AgentStageStatus {
    PENDING,
    CONTEXT_READY,
    DISPATCHING,
    RUNNING,
    RESULT_COLLECTING,
    VERIFYING,
    SUCCEEDED,
    FAILED_RETRYABLE,
    FAILED_NEEDS_HUMAN,
    SKIPPED,
    CANCELLED,
    RECOVERING;

    /**
     * 判断阶段是否已经结束。
     *
     * @return 已结束时返回 true
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED_NEEDS_HUMAN || this == SKIPPED || this == CANCELLED;
    }
}
