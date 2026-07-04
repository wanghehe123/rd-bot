package com.wish.rd.engine.agent;

/**
 * 多 Agent 工作流告警类型。
 */
public enum AgentWorkflowAlertType {
    STAGE_FAILED_RETRYABLE,
    STAGE_FAILED_NEEDS_HUMAN,
    PROVIDER_FALLBACK,
    QA_FAILED,
    DELIVERY_REVIEW_FAILED,
    PR_PUBLICATION_FAILED,
    EXPERIENCE_CAPTURE_FAILED
}
