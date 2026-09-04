package com.wish.rd.engine.project.memory.model;

/** High-priority security alert for project memory gate violations. */
public record ProjectMemorySecurityAlert(
        String projectId,
        ProjectMemorySecurityAlertType type,
        String message,
        String gateRevision,
        String datasetRevision,
        long observedAtEpochMillis
) {
    public ProjectMemorySecurityAlert {
        projectId = projectId == null ? "" : projectId.strip();
        type = type == null ? ProjectMemorySecurityAlertType.GATE_THRESHOLD_FAILURE : type;
        message = message == null ? "" : message.strip();
        gateRevision = gateRevision == null ? "" : gateRevision.strip();
        datasetRevision = datasetRevision == null ? "" : datasetRevision.strip();
        observedAtEpochMillis = Math.max(0L, observedAtEpochMillis);
    }
}
