package com.wish.rd.rag.knowledge.projection.model;

/**
 * 对账发现的种类。只记账，不构成远端写许可。
 */
public enum ReconcileFindingType {
    MISSING_REMOTE,
    STALE_REMOTE,
    ORPHAN_REMOTE,
    FOREIGN_OWNER,
    DRIFTED_MARKER,
    LEASE_EXPIRED
}
