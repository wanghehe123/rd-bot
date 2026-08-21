package com.wish.rd.engine.requirement.remediation.model;

public record AgentRemediationRound(
        String roundId,
        String taskId,
        AgentRemediationKind kind,
        int remediationNo,
        String sourceStageRunId,
        String sourceCommandId,
        String sourceResultHash,
        long sourceTaskVersion,
        long sourceFencingToken,
        String targetCodingStageRunId,
        int targetCodingAttemptNo,
        String targetQaStageRunId,
        int targetQaAttemptNo,
        String firstCommandId,
        String requestJson,
        String requestHash,
        Status status,
        long rowVersion,
        long createdAtEpochMillis
) {
    public enum Status { PLANNED, CLAIMED, DISPATCHED, COMPLETED, FAILED_NEEDS_HUMAN }

    public AgentRemediationRound {
        roundId = require(roundId, "roundId");
        taskId = require(taskId, "taskId");
        if (kind == null) throw new IllegalArgumentException("kind is required");
        if (remediationNo < 1 || remediationNo > kind.maximumRounds()) {
            throw new IllegalArgumentException("remediationNo exceeds kind limit");
        }
        sourceStageRunId = require(sourceStageRunId, "sourceStageRunId");
        sourceCommandId = require(sourceCommandId, "sourceCommandId");
        sourceResultHash = requireDigest(sourceResultHash, "sourceResultHash");
        if (sourceTaskVersion < 0 || sourceFencingToken <= 0) {
            throw new IllegalArgumentException("invalid source fencing identity");
        }
        targetCodingStageRunId = safe(targetCodingStageRunId);
        targetQaStageRunId = require(targetQaStageRunId, "targetQaStageRunId");
        firstCommandId = require(firstCommandId, "firstCommandId");
        if (targetQaAttemptNo < 1 || targetQaAttemptNo > 3) {
            throw new IllegalArgumentException("target QA attempt must be between 1 and 3");
        }
        if (kind == AgentRemediationKind.QA_PRODUCT_FIX) {
            targetCodingStageRunId = require(targetCodingStageRunId, "targetCodingStageRunId");
            if (targetCodingAttemptNo < 1 || targetCodingAttemptNo > 3) {
                throw new IllegalArgumentException("target Coding attempt must be between 1 and 3");
            }
        } else if (!targetCodingStageRunId.isEmpty() || targetCodingAttemptNo != 0) {
            throw new IllegalArgumentException("protocol retry must not target Coding");
        }
        requestJson = require(requestJson, "requestJson");
        if (requestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65_536) {
            throw new IllegalArgumentException("requestJson exceeds protocol limit");
        }
        requestHash = requireDigest(requestHash, "requestHash");
        status = status == null ? Status.PLANNED : status;
        if (rowVersion < 0 || createdAtEpochMillis < 0) {
            throw new IllegalArgumentException("invalid remediation version or time");
        }
    }

    private static String require(String value, String field) {
        String normalized = safe(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String requireDigest(String value, String field) {
        String normalized = require(value, field);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a sha256 digest");
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
