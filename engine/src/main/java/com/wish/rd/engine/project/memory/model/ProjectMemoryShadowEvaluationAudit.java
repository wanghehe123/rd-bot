package com.wish.rd.engine.project.memory.model;

import java.util.List;

/** Versioned audit bundle for shadow evaluation and PRIMARY gate binding. */
public record ProjectMemoryShadowEvaluationAudit(
        String projectId,
        String datasetRevision,
        String gateRevision,
        ProjectMemoryShadowMetrics metrics,
        boolean passed,
        List<String> failureReasons
) {
    public ProjectMemoryShadowEvaluationAudit {
        projectId = projectId == null ? "" : projectId.strip();
        datasetRevision = datasetRevision == null ? "" : datasetRevision.strip();
        gateRevision = gateRevision == null ? "" : gateRevision.strip();
        metrics = metrics == null
                ? new ProjectMemoryShadowMetrics("", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, List.of())
                : metrics;
        failureReasons = failureReasons == null ? List.of() : List.copyOf(failureReasons);
    }
}
