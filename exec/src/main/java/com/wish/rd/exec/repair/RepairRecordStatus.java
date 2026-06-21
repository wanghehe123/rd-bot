package com.wish.rd.exec.repair;

/**
 * 修复记录状态：覆盖 P0 可持久化的任务生命周期节点。
 */
public enum RepairRecordStatus {
    CREATED,
    RAG_READY,
    EXECUTING,
    VALIDATING,
    COMPLETED,
    FAILED
}
