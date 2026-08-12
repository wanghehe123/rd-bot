package com.wish.rd.engine.scheduling;

import com.wish.rd.engine.scheduling.model.FairScheduleLimits;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.engine.scheduling.model.StageScheduleCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        return select(candidates, inFlightByProject, inFlightByResource, limits, nowEpochMillis, Map.of());
    }

    /**
     * Selects a batch using optional project weights. A larger weight receives a proportionally
     * larger share while aging still lowers effective priority for every project.
     *
     * @param candidates pending candidates
     * @param inFlightByProject current in-flight count by project
     * @param inFlightByResource current in-flight count by resource
     * @param limits concurrency limits
     * @param nowEpochMillis ranking clock
     * @param projectWeights positive project weights, blank/missing means one
     * @return fair ordered claim batch
     */
    public List<StageScheduleCandidate> select(
            List<StageScheduleCandidate> candidates,
            Map<String, Integer> inFlightByProject,
            Map<ScheduleResourceClass, Integer> inFlightByResource,
            FairScheduleLimits limits,
            long nowEpochMillis,
            Map<String, Integer> projectWeights
    ) {
        return select(
                candidates,
                inFlightByProject,
                inFlightByResource,
                Map.of(),
                limits,
                nowEpochMillis,
                projectWeights
        );
    }

    /**
     * Selects a batch after considering every resource token and the provider-specific load of
     * commands that require a provider call. A command is admitted only when all of its resource
     * requirements fit together; claiming browser QA therefore also reserves its Docker and
     * provider capacity.
     *
     * @param candidates pending candidates
     * @param inFlightByProject current in-flight count by project
     * @param inFlightByResource current in-flight count by shared resource
     * @param inFlightByProvider current in-flight count by provider id
     * @param limits concurrency limits
     * @param nowEpochMillis ranking clock
     * @param projectWeights positive project weights, blank/missing means one
     * @return fair ordered claim batch
     */
    public List<StageScheduleCandidate> select(
            List<StageScheduleCandidate> candidates,
            Map<String, Integer> inFlightByProject,
            Map<ScheduleResourceClass, Integer> inFlightByResource,
            Map<String, Integer> inFlightByProvider,
            FairScheduleLimits limits,
            long nowEpochMillis,
            Map<String, Integer> projectWeights
    ) {
        Objects.requireNonNull(limits, "limits must not be null");
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> weights = projectWeights == null ? Map.of() : projectWeights;
        Map<String, Integer> projectLoad = new HashMap<>(
                inFlightByProject == null ? Map.of() : inFlightByProject);
        Map<ScheduleResourceClass, Integer> resourceLoad = new EnumMap<>(ScheduleResourceClass.class);
        if (inFlightByResource != null) {
            resourceLoad.putAll(inFlightByResource);
        }
        Map<String, Integer> providerLoad = new HashMap<>(
                inFlightByProvider == null ? Map.of() : inFlightByProvider);

        List<StageScheduleCandidate> remaining = new ArrayList<>();
        Set<String> commandIds = new HashSet<>();
        for (StageScheduleCandidate candidate : candidates) {
            if (candidate != null && commandIds.add(candidate.commandId())) {
                remaining.add(candidate);
            }
        }

        List<StageScheduleCandidate> selected = new ArrayList<>();
        while (!remaining.isEmpty() && selected.size() < limits.batchSize()) {
            StageScheduleCandidate candidate = remaining.stream()
                    .filter(value -> fitsProject(value, projectLoad, limits.maxPerProject()))
                    .filter(value -> fitsResources(value, resourceLoad, limits))
                    .filter(value -> fitsProvider(value, providerLoad, limits))
                    .min(rankComparator(nowEpochMillis, limits.agingMillis(), projectLoad, weights))
                    .orElse(null);
            if (candidate == null) {
                break;
            }
            selected.add(candidate);
            projectLoad.merge(candidate.projectId(), 1, Integer::sum);
            for (ScheduleResourceClass resource : candidate.resourceRequirements()) {
                resourceLoad.merge(resource, 1, Integer::sum);
            }
            if (candidate.requires(ScheduleResourceClass.PROVIDER)) {
                providerLoad.merge(providerKey(candidate.providerId()), 1, Integer::sum);
            }
            remaining.remove(candidate);
        }
        return List.copyOf(selected);
    }

    private static Comparator<StageScheduleCandidate> rankComparator(
            long now,
            long agingMillis,
            Map<String, Integer> projectLoad,
            Map<String, Integer> projectWeights
    ) {
        return Comparator
                .comparingDouble((StageScheduleCandidate c) ->
                        ((double) projectLoad.getOrDefault(c.projectId(), 0))
                                / Math.max(1, projectWeights.getOrDefault(c.projectId(), 1)))
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

    private static boolean fitsResources(
            StageScheduleCandidate candidate,
            Map<ScheduleResourceClass, Integer> resourceLoad,
            FairScheduleLimits limits
    ) {
        for (ScheduleResourceClass resource : candidate.resourceRequirements()) {
            int current = resourceLoad.getOrDefault(resource, 0);
            boolean fits = switch (resource) {
                case DOCKER -> current < limits.maxDocker();
                case BROWSER_QA -> current < limits.maxBrowserQa();
                case PROVIDER -> current < limits.maxProvider();
                case GENERIC -> true;
            };
            if (!fits) {
                return false;
            }
        }
        return true;
    }

    private static boolean fitsProvider(
            StageScheduleCandidate candidate,
            Map<String, Integer> providerLoad,
            FairScheduleLimits limits
    ) {
        return !candidate.requires(ScheduleResourceClass.PROVIDER)
                || providerLoad.getOrDefault(providerKey(candidate.providerId()), 0) < limits.maxPerProvider();
    }

    private static String providerKey(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return "_unassigned";
        }
        return providerId.strip();
    }
}
