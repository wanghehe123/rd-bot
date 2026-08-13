package com.wish.rd.rag.knowledge.projection.model;

/**
 * 对账发现的处置状态。隔离不等于删除许可。
 */
public enum ReconcileFindingStatus {
    OPEN,
    AUTO_REPAIRED,
    QUARANTINED,
    RESOLVED
}
