package com.wish.rd.engine.admin.trace.model;

/** One task's current execution trace projection. */
public record ExecutionTraceRecord(
        String taskId, String projectId, String projectName, String taskType, String taskStatus,
        String title, String currentRole, String currentStageStatus, String providerName,
        int progressCompleted, int progressTotal, int retryCount, boolean blocked,
        long elapsedMillis, long updateTimeEpochMillis
) { }
