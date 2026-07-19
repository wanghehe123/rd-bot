package com.wish.rd.engine.evaluation.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Aggregate counters for the evaluation console header. */
public record EvaluationRunOverview(
        long total,
        long active,
        long gatePassed,
        long incomplete,
        long failed
) {
    public EvaluationRunOverview {
        total = Math.max(0L, total);
        active = Math.max(0L, active);
        gatePassed = Math.max(0L, gatePassed);
        incomplete = Math.max(0L, incomplete);
        failed = Math.max(0L, failed);
    }

    /** Calculates the same global counters in explicit in-memory test mode. */
    public static EvaluationRunOverview from(List<EvaluationRun> runs, List<String> nonSmokeDatasetIds) {
        List<EvaluationRun> safeRuns = runs == null ? List.of() : runs;
        Set<String> deliveryDatasetIds = new HashSet<>(nonSmokeDatasetIds == null ? List.of() : nonSmokeDatasetIds);
        long active = safeRuns.stream().filter(run -> run.status().isActive()).count();
        long gatePassed = safeRuns.stream()
                .filter(run -> isDeliveryRun(run, deliveryDatasetIds))
                .filter(run -> "PASSED".equals(EvaluationRunQuery.gateStatusOf(run)))
                .count();
        long incomplete = safeRuns.stream()
                .filter(run -> "INCOMPLETE".equals(EvaluationRunQuery.gateStatusOf(run)))
                .count();
        long failed = safeRuns.stream().filter(run -> run.status() == EvaluationRunStatus.FAILED).count();
        return new EvaluationRunOverview(safeRuns.size(), active, gatePassed, incomplete, failed);
    }

    private static boolean isDeliveryRun(EvaluationRun run, Set<String> deliveryDatasetIds) {
        return run.config().source() == EvaluationSource.TASK_RUN
                || deliveryDatasetIds.contains(run.config().datasetId());
    }
}
