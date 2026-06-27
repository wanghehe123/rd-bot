package com.wish.rd.exec.repair.alert;

/**
 * 修复执行告警类型，由 exec 执行层使用，供后续通知、持久化和运营视图适配。
 */
public enum RepairAlertType {

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
    SECURITY_POLICY_REJECTED
}
