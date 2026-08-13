package com.wish.rd.rag.knowledge.projection.model;

import java.util.List;

/**
 * 管理面一次手工操作的结果。不携带原始远端响应。
 *
 * @param applied       是否改动了账本
 * @param outcome       稳定结果码，供 API 与前端分支
 * @param eventId       相关 outbox 行，没有时为空
 * @param operationStatus 操作后的 outbox 状态名，没有时为空
 * @param failedChecks  verify 未通过的检查名
 * @param message       已脱敏说明
 */
public record ProjectionAdminActionResult(
        boolean applied,
        String outcome,
        String eventId,
        String operationStatus,
        List<String> failedChecks,
        String message
) {

    public static final String NOTHING_TO_RETRY = "NOTHING_TO_RETRY";
    public static final String RETRIED = "RETRIED";
    public static final String VERIFIED = "VERIFIED";
    public static final String DRIFTED = "DRIFTED";
    public static final String REBUILT = "REBUILT";
    public static final String ALREADY_QUEUED = "ALREADY_QUEUED";
    public static final String REQUEUED = "REQUEUED";

    public ProjectionAdminActionResult {
        outcome = outcome == null ? "" : outcome;
        eventId = eventId == null ? "" : eventId;
        operationStatus = operationStatus == null ? "" : operationStatus;
        failedChecks = failedChecks == null ? List.of() : List.copyOf(failedChecks);
        message = message == null ? "" : message;
    }

    public static ProjectionAdminActionResult nothingToRetry() {
        return new ProjectionAdminActionResult(false, NOTHING_TO_RETRY, "", "", List.of(), "nothing to retry");
    }

    public static ProjectionAdminActionResult retried(KnowledgeExternalIndexOperation operation) {
        return new ProjectionAdminActionResult(
                true, RETRIED, operation.eventId(), operation.status().name(), List.of(), "");
    }

    public static ProjectionAdminActionResult verified(boolean inSync, List<String> failedChecks) {
        return new ProjectionAdminActionResult(
                true,
                inSync ? VERIFIED : DRIFTED,
                "",
                "",
                failedChecks,
                inSync ? "" : "remote version does not match the ledger");
    }

    public static ProjectionAdminActionResult rebuilt(KnowledgeExternalIndexOperation operation) {
        return new ProjectionAdminActionResult(
                true, REBUILT, operation.eventId(), operation.status().name(), List.of(), "");
    }

    public static ProjectionAdminActionResult alreadyQueued(KnowledgeExternalIndexOperation operation) {
        return new ProjectionAdminActionResult(
                false, ALREADY_QUEUED, operation.eventId(), operation.status().name(), List.of(), "already queued");
    }

    public static ProjectionAdminActionResult requeued(KnowledgeExternalIndexOperation operation) {
        return new ProjectionAdminActionResult(
                true, REQUEUED, operation.eventId(), operation.status().name(), List.of(), "");
    }
}
