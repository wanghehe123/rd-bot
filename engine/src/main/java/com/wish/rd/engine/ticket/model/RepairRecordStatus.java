package com.wish.rd.engine.ticket.model;

/**
 * 工单修复编排使用的修复记录状态。
 */
public enum RepairRecordStatus {
    CREATED,
    QUEUED,
    CONTEXT_COLLECTING,
    CONTEXT_READY,
    WAITING_FOR_INFO,
    RAG_READY,
    EXECUTING,
    VALIDATING,
    COMMITTED,
    MERGED,
    COMPLETED,
    FAILED
}
