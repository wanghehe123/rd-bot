package com.wish.rd.engine.retry.model;

import java.util.List;

/** Immutable input for creating one checkpoint's first durable command. */
public record InitializeRequirementRetryCommand(
        TaskRetryCheckpoint checkpoint,
        TaskRetryRoute route,
        String commandId,
        int maxAttempts,
        long deadlineEpochMillis,
        String projectId,
        String providerId,
        String priority,
        String targetRetryBindingId,
        List<TaskRetryAttemptBinding> plannedBindings,
        long nowEpochMillis
) {
    public InitializeRequirementRetryCommand {
        if (checkpoint == null || checkpoint.status() != TaskRetryCheckpointStatus.CREATED) {
            throw new IllegalArgumentException("retry initialization requires a CREATED checkpoint");
        }
        if (route == null) {
            throw new IllegalArgumentException("retry initialization route must not be null");
        }
        commandId = require(commandId, "commandId");
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        deadlineEpochMillis = Math.max(0L, deadlineEpochMillis);
        projectId = projectId == null || projectId.isBlank() ? "_default" : projectId.strip();
        providerId = safe(providerId);
        priority = priority == null || priority.isBlank() ? "P2" : priority.strip();
        targetRetryBindingId = safe(targetRetryBindingId);
        plannedBindings = List.copyOf(plannedBindings == null ? List.of() : plannedBindings);
        String normalizedTargetBindingId = targetRetryBindingId;
        boolean targetRequired = route.primaryAttemptKind() != null;
        if (targetRequired != !normalizedTargetBindingId.isBlank()) {
            throw new IllegalArgumentException(targetRequired
                    ? "retry route primary attempt requires a target binding"
                    : "retry infrastructure route must not carry a target binding");
        }
        if (!normalizedTargetBindingId.isBlank() && plannedBindings.stream().noneMatch(binding ->
                binding.bindingId().equals(normalizedTargetBindingId)
                        && binding.checkpointId().equals(checkpoint.checkpointId()))) {
            throw new IllegalArgumentException("retry target binding must be planned for its checkpoint");
        }
    }

    private static String require(String value, String field) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
