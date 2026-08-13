package com.wish.rd.rag.knowledge.projection.model;

/**
 * 单篇存量回填的显式结果。禁止用布尔值把跳过吞掉。
 */
public enum InventoryBackfillStatus {
    APPLIED,
    SKIPPED_ALREADY_BOUND,
    SKIPPED_NOT_ELIGIBLE,
    SKIPPED_CONCURRENT_MODIFICATION,
    FAILED
}
