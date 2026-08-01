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
        boolean dispatchPaused,
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
                "created", 0, config, 0, 0, 0, false, "{}", "", "", false, 0L,
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
        // Python 流水线从 RECORDING 起算，coding benchmark campaign 从 RUNNING_TRIALS 起算，
        // 两条链路都必须在首次进入执行阶段时落 startedAt，否则耗时统计恒为 0。
        if ((target == EvaluationRunStatus.RECORDING || target == EvaluationRunStatus.RUNNING_TRIALS)
                && startedAt == 0L) {
            startedAt = now;
        }
        long finishedAt = target.isTerminal() ? now : finishedAtEpochMillis;
        return new EvaluationRun(runId, name, attemptNo, parentRunId, target, safe(message),
                progress(target), config, sampleCount, passedSampleCount, failedSampleCount, overallPassed,
                metricsJson, safe(category), safe(error), dispatchPaused, version + 1L, createdAtEpochMillis,
                startedAt, finishedAt, now);
    }

    /** Returns the successful terminal snapshot with parsed score fields. */
    public EvaluationRun withResult(EvaluationExecutionResult result, long now) {
        return new EvaluationRun(runId, name, attemptNo, parentRunId, EvaluationRunStatus.SUCCEEDED,
                "evaluation completed", 100, config, result.sampleCount(), result.passedSampleCount(),
                result.failedSampleCount(), result.overallPassed(), result.metricsJson(), "", "",
                dispatchPaused, version + 1L, createdAtEpochMillis,
                startedAtEpochMillis == 0L ? now : startedAtEpochMillis,
                now, now);
    }

    /**
     * 返回仅切换派发暂停标记的新快照，状态图与进度保持不变。
     *
     * @param paused true 表示暂停继续派发新 trial，false 表示恢复派发
     * @param now    本次变更的时间戳（epoch millis）
     * @return 版本号递增后的不可变快照
     */
    public EvaluationRun withDispatchPaused(boolean paused, long now) {
        return new EvaluationRun(runId, name, attemptNo, parentRunId, status, phaseMessage, progressPercent,
                config, sampleCount, passedSampleCount, failedSampleCount, overallPassed,
                metricsJson, errorCategory, errorMessage, paused, version + 1L,
                createdAtEpochMillis, startedAtEpochMillis, finishedAtEpochMillis, now);
    }

    /**
     * Returns a snapshot with refreshed trial sample counters while leaving status unchanged.
     *
     * @param sampleCount      total terminal trials counted toward progress
     * @param passedSampleCount trials that reached {@code SUCCEEDED}
     * @param failedSampleCount trials that reached {@code FAILED} or {@code CANCELLED}
     * @param now              update timestamp (epoch millis)
     * @return version-incremented immutable snapshot
     */
    public EvaluationRun withTrialSampleProgress(
            int sampleCount,
            int passedSampleCount,
            int failedSampleCount,
            long now
    ) {
        return new EvaluationRun(runId, name, attemptNo, parentRunId, status, phaseMessage, progressPercent,
                config, sampleCount, passedSampleCount, failedSampleCount, overallPassed,
                metricsJson, errorCategory, errorMessage, dispatchPaused, version + 1L,
                createdAtEpochMillis, startedAtEpochMillis, finishedAtEpochMillis, now);
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
