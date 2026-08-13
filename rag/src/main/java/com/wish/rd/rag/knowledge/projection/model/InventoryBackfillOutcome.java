package com.wish.rd.rag.knowledge.projection.model;

import java.util.Objects;

/**
 * 一篇文档的回填结果，附原因文本。
 */
public record InventoryBackfillOutcome(
        String documentId,
        InventoryBackfillStatus status,
        String reason
) {

    public InventoryBackfillOutcome {
        Objects.requireNonNull(documentId, "documentId must not be null");
        status = status == null ? InventoryBackfillStatus.SKIPPED_NOT_ELIGIBLE : status;
        reason = reason == null ? "" : reason;
    }

    public static InventoryBackfillOutcome applied(String documentId) {
        return new InventoryBackfillOutcome(documentId, InventoryBackfillStatus.APPLIED, "backfill applied");
    }

    public static InventoryBackfillOutcome skipped(
            String documentId,
            InventoryBackfillStatus status,
            String reason
    ) {
        return new InventoryBackfillOutcome(documentId, status, reason);
    }

    public static InventoryBackfillOutcome failed(String documentId, String reason) {
        return new InventoryBackfillOutcome(documentId, InventoryBackfillStatus.FAILED, reason);
    }
}
