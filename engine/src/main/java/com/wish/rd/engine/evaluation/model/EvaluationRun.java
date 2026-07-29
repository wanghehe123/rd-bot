package com.wish.rd.engine.evaluation.model;

/** Persistent control-plane snapshot for one local evaluation attempt. */
public record EvaluationRun(
        String runId,
        String name,
        int attemptNo,
        String parentRunId,
        EvaluationRunStatus status,
        String phaseMessage,
        int progressPercent,
        EvaluationRunConfig config,
        int sampleCount,
        int passedSampleCount,
        int failedSampleCount,
        boolean overallPassed,
        String metricsJson,
        String errorCategory,
        String errorMessage,
        long version,
        long createdAtEpochMillis,
        long startedAtEpochMillis,
        long finishedAtEpochMillis,
        long updatedAtEpochMillis
) {
    /** Creates an immutable initial run snapshot. */
    public static EvaluationRun created(
            String runId,
            EvaluationRunConfig config,
            int attemptNo,
            String parentRunId,
            long now
    ) {
        return new EvaluationRun(runId, config.name(), attemptNo, safe(parentRunId), EvaluationRunStatus.CREATED,
                "created", 0, config, 0, 0, 0, false, "{}", "", "", 0L,
                now, 0L, 0L, now);
    }

    /** Returns the snapshot after a validated state transition. */
    public EvaluationRun withStatus(
            EvaluationRunStatus target,
            String message,
            String category,
            String error,
            long now
    ) {
        long startedAt = startedAtEpochMillis;
        if (target == EvaluationRunStatus.RECORDING && startedAt == 0L) {
            startedAt = now;
        }
        long finishedAt = target.isTerminal() ? now : finishedAtEpochMillis;
        return new EvaluationRun(runId, name, attemptNo, parentRunId, target, safe(message),
                progress(target), config, sampleCount, passedSampleCount, failedSampleCount, overallPassed,
                metricsJson, safe(category), safe(error), version + 1L, createdAtEpochMillis,
                startedAt, finishedAt, now);
    }

    /** Returns the successful terminal snapshot with parsed score fields. */
    public EvaluationRun withResult(EvaluationExecutionResult result, long now) {
        return new EvaluationRun(runId, name, attemptNo, parentRunId, EvaluationRunStatus.SUCCEEDED,
                "evaluation completed", 100, config, result.sampleCount(), result.passedSampleCount(),
                result.failedSampleCount(), result.overallPassed(), result.metricsJson(), "", "",
                version + 1L, createdAtEpochMillis, startedAtEpochMillis == 0L ? now : startedAtEpochMillis,
                now, now);
    }

    private static int progress(EvaluationRunStatus status) {
        return switch (status) {
            case CREATED -> 0;
            case QUEUED -> 5;
            case PREPARING -> 12;
            case RUNNING_TRIALS -> 45;
            case RECORDING -> 20;
            case SCORING -> 55;
            case REPORTING -> 78;
            case DIFFING -> 90;
            case CANCEL_REQUESTED -> 95;
            case SUCCEEDED, FAILED, CANCELLED -> 100;
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
