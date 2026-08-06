package com.wish.rd.engine.scheduling;

/**
 * One pending stage/work unit competing for a fair schedule claim.
 *
 * @param commandId     stable command id
 * @param taskId        RD task id
 * @param projectId     project id used for fairness
 * @param priorityRank  0 = P0 (highest), larger = lower priority
 * @param enqueuedAtEpochMillis enqueue time for aging
 * @param resourceClass resource token class
 * @param taskVersion   task fencing version carried on the command
 */
public record StageScheduleCandidate(
        String commandId,
        String taskId,
        String projectId,
        int priorityRank,
        long enqueuedAtEpochMillis,
        ScheduleResourceClass resourceClass,
        long taskVersion
) {

    public StageScheduleCandidate {
        commandId = require(commandId, "commandId");
        taskId = require(taskId, "taskId");
        projectId = projectId == null || projectId.isBlank() ? "_default" : projectId.strip();
        priorityRank = Math.max(0, priorityRank);
        enqueuedAtEpochMillis = Math.max(0L, enqueuedAtEpochMillis);
        resourceClass = resourceClass == null ? ScheduleResourceClass.GENERIC : resourceClass;
        taskVersion = Math.max(0L, taskVersion);
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
