package com.wish.rd.engine.scheduling.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * One pending stage/work unit competing for a fair schedule claim.
 *
 * @param commandId     stable command id
 * @param taskId        RD task id
 * @param projectId     project id used for fairness
 * @param priorityRank  0 = P0 (highest), larger = lower priority
 * @param enqueuedAtEpochMillis enqueue time for aging
 * @param resourceClass primary resource token class retained for legacy metrics
 * @param resourceRequirements all resource tokens that must be available together
 * @param providerId provider token key when {@code resourceRequirements} contains PROVIDER
 * @param taskVersion   task fencing version carried on the command
 */
public record StageScheduleCandidate(
        String commandId,
        String taskId,
        String projectId,
        int priorityRank,
        long enqueuedAtEpochMillis,
        ScheduleResourceClass resourceClass,
        Set<ScheduleResourceClass> resourceRequirements,
        String providerId,
        long taskVersion
) {

    public StageScheduleCandidate {
        commandId = require(commandId, "commandId");
        taskId = require(taskId, "taskId");
        projectId = projectId == null || projectId.isBlank() ? "_default" : projectId.strip();
        priorityRank = Math.max(0, priorityRank);
        enqueuedAtEpochMillis = Math.max(0L, enqueuedAtEpochMillis);
        resourceClass = resourceClass == null ? ScheduleResourceClass.GENERIC : resourceClass;
        resourceRequirements = normalizeRequirements(resourceRequirements, resourceClass);
        if (resourceClass == ScheduleResourceClass.GENERIC
                && resourceRequirements.size() > 1) {
            resourceClass = resourceRequirements.stream()
                    .filter(resource -> resource != ScheduleResourceClass.GENERIC)
                    .findFirst()
                    .orElse(ScheduleResourceClass.GENERIC);
        }
        providerId = providerId == null ? "" : providerId.strip();
        taskVersion = Math.max(0L, taskVersion);
    }

    /** Backward-compatible candidate constructor for a single resource class. */
    public StageScheduleCandidate(
            String commandId,
            String taskId,
            String projectId,
            int priorityRank,
            long enqueuedAtEpochMillis,
            ScheduleResourceClass resourceClass,
            long taskVersion
    ) {
        this(
                commandId,
                taskId,
                projectId,
                priorityRank,
                enqueuedAtEpochMillis,
                resourceClass,
                Set.of(resourceClass == null ? ScheduleResourceClass.GENERIC : resourceClass),
                "",
                taskVersion
        );
    }

    /** Returns whether this candidate consumes the supplied shared resource token. */
    public boolean requires(ScheduleResourceClass resourceClass) {
        return resourceClass != null && resourceRequirements.contains(resourceClass);
    }

    private static Set<ScheduleResourceClass> normalizeRequirements(
            Set<ScheduleResourceClass> values,
            ScheduleResourceClass fallback
    ) {
        EnumSet<ScheduleResourceClass> normalized = EnumSet.noneOf(ScheduleResourceClass.class);
        if (values != null) {
            for (ScheduleResourceClass value : values) {
                if (value != null) {
                    normalized.add(value);
                }
            }
        }
        if (normalized.isEmpty()) {
            normalized.add(fallback == null ? ScheduleResourceClass.GENERIC : fallback);
        }
        if (normalized.size() > 1) {
            normalized.remove(ScheduleResourceClass.GENERIC);
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
