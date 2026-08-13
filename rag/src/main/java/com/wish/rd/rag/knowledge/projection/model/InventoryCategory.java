package com.wish.rd.rag.knowledge.projection.model;

/**
 * 存量审计分类，判定顺序即枚举声明顺序（计划 D5）。
 */
public enum InventoryCategory {
    TOMBSTONE,
    SUPERSEDED,
    DUPLICATE_UNRESOLVED,
    EXCLUDED_BASE_INACTIVE,
    EXCLUDED_LOCAL_ONLY,
    EXCLUDED_EMPTY,
    FAILED,
    IN_SYNC,
    PROJECTING,
    PENDING_BACKFILL
}
