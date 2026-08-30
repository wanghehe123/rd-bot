package com.wish.rd.engine.project.memory.model;

import java.util.List;

/** Computed shadow metrics for one project evaluation run. */
public record ProjectMemoryShadowMetrics(
        String projectId,
        int crossProjectLeakage,
        double recallAtK,
        double precisionAtK,
        double staleConflictRate,
        double abstentionRate,
        double duplicateContextRate,
        double sourceCoverage,
        long p95LatencyMillis,
        int examinedRows,
        double tokenShare,
        int k,
        List<String> failedThresholds
) {
    public ProjectMemoryShadowMetrics {
        projectId = projectId == null ? "" : projectId.strip();
        crossProjectLeakage = Math.max(0, crossProjectLeakage);
        examinedRows = Math.max(0, examinedRows);
        k = k <= 0 ? 3 : k;
        failedThresholds = failedThresholds == null ? List.of() : List.copyOf(failedThresholds);
    }

    public boolean passed() {
        return crossProjectLeakage == 0 && failedThresholds.isEmpty();
    }
}
