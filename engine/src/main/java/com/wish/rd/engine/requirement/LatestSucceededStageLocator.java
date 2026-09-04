package com.wish.rd.engine.requirement;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Picks the latest {@code SUCCEEDED} stage run for one role.
 *
 * <p>{@link com.wish.rd.engine.requirement.RequirementDeliveryEngine} uses this to bind
 * {@code HOST_VERIFY} to the coding snapshot that actually succeeded. Recency matches the
 * existing orchestrator comparator: attempt, then create time, then stage-run id.
 */
public final class LatestSucceededStageLocator {

    static final Comparator<AgentStageRun> RECENCY = Comparator
            .comparingInt(AgentStageRun::attemptNo)
            .thenComparingLong(AgentStageRun::createTimeEpochMillis)
            .thenComparing(AgentStageRun::stageRunId);

    /**
     * Returns the latest succeeded run for {@code role}, if any.
     *
     * @param stages task stage runs, possibly empty
     * @param role role to select
     * @return succeeded snapshot or empty
     */
    public Optional<AgentStageRun> find(List<AgentStageRun> stages, AgentRole role) {
        if (stages == null || role == null) {
            return Optional.empty();
        }
        return stages.stream()
                .filter(stage -> stage.role() == role && stage.status() == AgentStageStatus.SUCCEEDED)
                .max(RECENCY);
    }

    /**
     * Returns the latest succeeded run or fails closed.
     *
     * @param stages task stage runs
     * @param role role to select
     * @param taskId owning task id used in the error
     * @return succeeded snapshot
     */
    public AgentStageRun require(List<AgentStageRun> stages, AgentRole role, String taskId) {
        return find(stages, role).orElseThrow(() -> new IllegalStateException(
                "succeeded " + role + " stage missing for HOST_VERIFY: " + taskId));
    }
}
