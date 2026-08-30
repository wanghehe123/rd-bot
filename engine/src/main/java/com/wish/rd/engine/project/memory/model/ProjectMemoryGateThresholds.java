package com.wish.rd.engine.project.memory.model;

/** Frozen shadow gate thresholds bound to a dataset revision. */
public record ProjectMemoryGateThresholds(
        String gateRevision,
        String datasetRevision,
        int maxCrossProjectLeakage,
        double minRecallAtK,
        double minPrecisionAtK,
        double maxStaleConflictRate,
        double maxAbstentionRate,
        double maxDuplicateContextRate,
        double minSourceCoverage,
        long maxP95LatencyMillis,
        double maxTokenShare,
        int defaultK
) {
    public ProjectMemoryGateThresholds {
        gateRevision = required(gateRevision, "gateRevision");
        datasetRevision = required(datasetRevision, "datasetRevision");
        maxCrossProjectLeakage = Math.max(0, maxCrossProjectLeakage);
        minRecallAtK = clamp(minRecallAtK);
        minPrecisionAtK = clamp(minPrecisionAtK);
        maxStaleConflictRate = clamp(maxStaleConflictRate);
        maxAbstentionRate = clamp(maxAbstentionRate);
        maxDuplicateContextRate = clamp(maxDuplicateContextRate);
        minSourceCoverage = clamp(minSourceCoverage);
        maxP95LatencyMillis = Math.max(0L, maxP95LatencyMillis);
        maxTokenShare = clamp(maxTokenShare);
        defaultK = defaultK <= 0 ? 3 : defaultK;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
