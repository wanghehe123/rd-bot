package com.wish.rd.exec.repair.alert.model;

/**
 * 修复执行告警类型，由 exec 执行层使用，供后续通知、持久化和运营视图适配。
 */
public enum RepairAlertType {

    /** Task reached its configured successful terminal state. */
    TASK_COMPLETED,

    /** Task is waiting for human intervention. */
    TASK_BLOCKED,

    /** Task reached a failed terminal state. */
    TASK_FAILED,

    /** Task or stage exhausted its configured retries. */
    RETRY_EXHAUSTED,

    /**
     * 执行耗时超过告警阈值，但默认不终止执行。
     */
    TIMEOUT_WARNING,

    /**
     * 预估预算超过告警阈值，但默认不终止执行。
     */
    BUDGET_WARNING,

    /**
     * 执行结果缺少预期产物。
     */
    MISSING_ARTIFACT,

    /**
     * 执行结果未通过结构化校验。
     */
    VALIDATION_FAILED,

    /**
     * 模型供应商熔断打开，执行会跳过该供应商。
     */
    MODEL_CIRCUIT_OPEN,

    /**
     * 队列消息超过重试上限后进入死信。
     */
    QUEUE_DEAD_LETTERED,

    /**
     * PR 创建失败。
     */
    PR_CREATE_FAILED,

    /**
     * 工单状态或评论回写失败。
     */
    TICKET_WRITE_BACK_FAILED,

    /**
     * 知识刷新失败。
     */
    KNOWLEDGE_REFRESH_FAILED,

    /**
     * 执行被安全策略拒绝。
     */
    SECURITY_POLICY_REJECTED,

    /**
     * Agent 阶段发生可重试失败。
     */
    STAGE_FAILED_RETRYABLE,

    /**
     * Agent 阶段失败且需要人工处理。
     */
    STAGE_FAILED_NEEDS_HUMAN,

    /**
     * 模型供应商发生降级切换。
     */
    PROVIDER_FALLBACK,

    /**
     * 策略门禁等待人工审批。
     */
    POLICY_WAITING_APPROVAL,

    /**
     * QA 阶段真实验收失败。
     */
    QA_FAILED,

    /**
     * 交付复核失败。
     */
    DELIVERY_REVIEW_FAILED,

    /**
     * 交付复核通过后的 PR 发布失败。
     */
    PR_PUBLICATION_FAILED,

    /**
     * 经验沉淀失败。
     */
    EXPERIENCE_CAPTURE_FAILED,

    /**
     * 多 Agent 工作流进入死信。
     */
    WORKFLOW_DEAD_LETTERED,

    /**
     * Skill 策略拒绝安装或使用。
     */
    SKILL_POLICY_REJECTED
}
