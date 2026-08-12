package com.wish.rd.engine.retry.model;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;

/** Persisted checkpoint and exact first command returned by retry initialization. */
public record RequirementRetryDispatchResult(
        TaskRetryCheckpoint checkpoint,
        RequirementStageCommand firstCommand,
        boolean replayed
) {
    public RequirementRetryDispatchResult {
        if (checkpoint == null || checkpoint.status() != TaskRetryCheckpointStatus.DISPATCHED) {
            throw new IllegalArgumentException("retry dispatch result requires a DISPATCHED checkpoint");
        }
        if (firstCommand == null || !checkpoint.dispatchCommandId().equals(firstCommand.commandId())) {
            throw new IllegalArgumentException("retry dispatch result command must match checkpoint dispatch identity");
        }
    }
}
