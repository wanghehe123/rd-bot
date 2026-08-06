package com.wish.rd.engine.scheduling;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Selects the next stage schedule batch using priority + aging + project fairness.
 * Does not claim leases; callers persist claims with SKIP LOCKED separately.
 */
public final class FairScheduleSelector {

    /**
     * Selects up to {@link FairScheduleLimits#batchSize()} runnable candidates.
     *
     * @param candidates          pending candidates
     * @param inFlightByProject   current in-flight count by project
     * @param inFlightByResource  current in-flight count by resource class
     * @param limits              concurrency limits
     * @param nowEpochMillis      ranking clock
     * @return ordered claim batch
     */
    public List<StageScheduleCandidate> select(
            List<StageScheduleCandidate> candidates,
            Map<String, Integer> inFlightByProject,
            Map<ScheduleResourceClass, Integer> inFlightByResource,
            FairScheduleLimits limits,
            long nowEpochMillis
    ) {
        Objects.requireNonNull(limits, "limits must not be null");
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> projectLoad = new HashMap<>(
                inFlightByProject == null ? Map.of() : inFlightByProject);
        Map<ScheduleResourceClass, Integer> resourceLoad = new EnumMap<>(ScheduleResourceClass.class);
        if (inFlightByResource != null) {
            resourceLoad.putAll(inFlightByResource);
        }

        List<StageScheduleCandidate> ranked = candidates.stream()
                .filter(Objects::nonNull)
                .sorted(rankComparator(nowEpochMillis, limits.agingMillis(), projectLoad))
                .toList();

        List<StageScheduleCandidate> selected = new ArrayList<>();
        for (StageScheduleCandidate candidate : ranked) {
            if (selected.size() >= limits.batchSize()) {
                break;
            }
            if (!fitsProject(candidate, projectLoad, limits.maxPerProject())) {
                continue;
            }
            if (!fitsResource(candidate, resourceLoad, limits)) {
                continue;
            }
            selected.add(candidate);
            projectLoad.merge(candidate.projectId(), 1, Integer::sum);
            resourceLoad.merge(candidate.resourceClass(), 1, Integer::sum);
        }
        return List.copyOf(selected);
    }

    private static Comparator<StageScheduleCandidate> rankComparator(
            long now,
            long agingMillis,
            Map<String, Integer> projectLoad
    ) {
        return Comparator
                .comparingInt((StageScheduleCandidate c) -> projectLoad.getOrDefault(c.projectId(), 0))
                .thenComparingDouble(c -> effectivePriority(c, now, agingMillis))
                .thenComparingLong(StageScheduleCandidate::enqueuedAtEpochMillis)
                .thenComparing(StageScheduleCandidate::commandId);
    }

    /**
     * Lower is better. Aging reduces effective priority so old low-priority work is not starved.
     */
    static double effectivePriority(StageScheduleCandidate candidate, long now, long agingMillis) {
        long age = Math.max(0L, now - candidate.enqueuedAtEpochMillis());
        double agingBoost = (double) age / (double) agingMillis;
        return candidate.priorityRank() - agingBoost;
    }

    private static boolean fitsProject(
            StageScheduleCandidate candidate,
            Map<String, Integer> projectLoad,
            int maxPerProject
    ) {
        return projectLoad.getOrDefault(candidate.projectId(), 0) < maxPerProject;
    }

    private static boolean fitsResource(
            StageScheduleCandidate candidate,
            Map<ScheduleResourceClass, Integer> resourceLoad,
            FairScheduleLimits limits
    ) {
        int current = resourceLoad.getOrDefault(candidate.resourceClass(), 0);
        return switch (candidate.resourceClass()) {
            case DOCKER -> current < limits.maxDocker();
            case BROWSER_QA -> current < limits.maxBrowserQa();
            case PROVIDER -> current < limits.maxProvider();
            case GENERIC -> true;
        };
    }
}
