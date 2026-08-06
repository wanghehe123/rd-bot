package com.wish.rd.engine.scheduling;

import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJob;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJobStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Maps durable requirement-delivery jobs onto {@link FairScheduleSelector} for recovery/dispatch ticks.
 * Project ids come from a caller-supplied lookup until jobs carry {@code projectId} natively.
 */
public final class FairRequirementDeliveryClaimPlanner {

    private final FairScheduleSelector selector;

    public FairRequirementDeliveryClaimPlanner(FairScheduleSelector selector) {
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
    }

    public FairRequirementDeliveryClaimPlanner() {
        this(new FairScheduleSelector());
    }

    /**
     * Selects an ordered claim batch from recoverable jobs.
     *
     * @param claimable        PENDING / FAILED_RETRYABLE / expired RUNNING candidates
     * @param inFlight         currently RUNNING (non-expired) jobs for load accounting
     * @param projectIdOfTask  resolves fairness key; blank → {@code _default}
     * @param limits           concurrency caps
     * @param nowEpochMillis   ranking clock
     * @return jobs to claim this tick, fair-ordered
     */
    public List<RequirementDeliveryJob> plan(
            List<RequirementDeliveryJob> claimable,
            List<RequirementDeliveryJob> inFlight,
            Function<String, String> projectIdOfTask,
            FairScheduleLimits limits,
            long nowEpochMillis
    ) {
        Objects.requireNonNull(limits, "limits must not be null");
        Function<String, String> projects = projectIdOfTask == null
                ? taskId -> "_default"
                : projectIdOfTask;

        Map<String, Integer> projectLoad = new HashMap<>();
        Map<ScheduleResourceClass, Integer> resourceLoad = new HashMap<>();
        if (inFlight != null) {
            for (RequirementDeliveryJob job : inFlight) {
                if (job == null || job.status() != RequirementDeliveryJobStatus.RUNNING) {
                    continue;
                }
                if (job.isExpiredRunning(nowEpochMillis)) {
                    continue;
                }
                String projectId = normalizeProject(projects.apply(job.taskId()));
                projectLoad.merge(projectId, 1, Integer::sum);
                resourceLoad.merge(ScheduleResourceClass.GENERIC, 1, Integer::sum);
            }
        }

        List<StageScheduleCandidate> candidates = new ArrayList<>();
        Map<String, RequirementDeliveryJob> byCommand = new HashMap<>();
        if (claimable != null) {
            for (RequirementDeliveryJob job : claimable) {
                if (job == null) {
                    continue;
                }
                String projectId = normalizeProject(projects.apply(job.taskId()));
                StageScheduleCandidate candidate = new StageScheduleCandidate(
                        job.jobId(),
                        job.taskId(),
                        projectId,
                        Math.max(0, job.attemptNo()),
                        job.createTimeEpochMillis(),
                        ScheduleResourceClass.GENERIC,
                        0L
                );
                candidates.add(candidate);
                byCommand.put(candidate.commandId(), job);
            }
        }

        List<StageScheduleCandidate> selected = selector.select(
                candidates, projectLoad, resourceLoad, limits, nowEpochMillis
        );
        List<RequirementDeliveryJob> planned = new ArrayList<>(selected.size());
        for (StageScheduleCandidate candidate : selected) {
            RequirementDeliveryJob job = byCommand.get(candidate.commandId());
            if (job != null) {
                planned.add(job);
            }
        }
        return List.copyOf(planned);
    }

    private static String normalizeProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return "_default";
        }
        return projectId.strip();
    }
}
