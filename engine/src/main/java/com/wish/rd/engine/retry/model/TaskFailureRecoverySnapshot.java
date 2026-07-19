package com.wish.rd.engine.retry.model;

import com.wish.rd.rag.runtime.model.RdTaskStatus;

import java.util.List;

/** Read-only recovery view that joins the resolved retry point with its diagnostic evidence. */
public record TaskFailureRecoverySnapshot(
        String taskId,
        RdTaskStatus sourceTaskStatus,
        TaskRetryPoint retryPoint,
        String failedStageStatus,
        int failedAttemptNo,
        String providerName,
        String errorCategory,
        String errorMessage,
        TaskFailureDiagnostic diagnostic,
        String rawResultArtifactId,
        String rawResultContentHash,
        String rawResultPreview,
        List<TaskRetryCheckpoint> history
) {

    public TaskFailureRecoverySnapshot {
        taskId = safe(taskId);
        if (sourceTaskStatus == null) {
            throw new IllegalArgumentException("sourceTaskStatus must not be null");
        }
        if (retryPoint == null) {
            throw new IllegalArgumentException("retryPoint must not be null");
        }
        failedStageStatus = safe(failedStageStatus);
        failedAttemptNo = Math.max(0, failedAttemptNo);
        providerName = safe(providerName);
        errorCategory = safe(errorCategory);
        errorMessage = safe(errorMessage);
        if (diagnostic == null) {
            throw new IllegalArgumentException("diagnostic must not be null");
        }
        rawResultArtifactId = safe(rawResultArtifactId);
        rawResultContentHash = safe(rawResultContentHash);
        rawResultPreview = safe(rawResultPreview);
        history = List.copyOf(history == null ? List.of() : history);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
