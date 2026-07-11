package com.wish.rd.rag.project.alert.model;

/** 项目可订阅的 RD 任务告警事件。 */
public enum RdProjectAlertEventType {
    TASK_COMPLETED,
    TASK_BLOCKED,
    TASK_FAILED,
    RETRY_EXHAUSTED,
    BUDGET_EXCEEDED,
    QA_FAILED
}
