package com.wish.rd.rag.knowledge.projection.model;

/**
 * 取代一组重复文档的结果。
 */
public record InventorySupersedeOutcome(InventorySupersedeStatus status, String reason) {

    public InventorySupersedeOutcome {
        status = status == null ? InventorySupersedeStatus.CONFLICT : status;
        reason = reason == null ? "" : reason;
    }

    public static InventorySupersedeOutcome applied() {
        return new InventorySupersedeOutcome(InventorySupersedeStatus.APPLIED, "supersede applied");
    }

    public static InventorySupersedeOutcome conflict(String reason) {
        return new InventorySupersedeOutcome(InventorySupersedeStatus.CONFLICT, reason);
    }

    public boolean appliedSuccessfully() {
        return status == InventorySupersedeStatus.APPLIED;
    }
}
