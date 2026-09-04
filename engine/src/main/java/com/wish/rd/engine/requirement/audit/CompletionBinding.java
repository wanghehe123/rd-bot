package com.wish.rd.engine.requirement.audit;

/**
 * Completion binding written in the same transaction as a {@code COMPLETED} mutation.
 *
 * @param taskId task id
 * @param auditRunId audit run that supported completion
 * @param stateVersion audited state version
 * @param stateHash audited state hash
 */
public record CompletionBinding(
        String taskId,
        String auditRunId,
        long stateVersion,
        String stateHash
) {
    public CompletionBinding {
        taskId = requireText(taskId, "taskId");
        auditRunId = requireText(auditRunId, "auditRunId");
        if (stateVersion < 1L) {
            throw new IllegalArgumentException("stateVersion must be >= 1");
        }
        stateHash = requireText(stateHash, "stateHash");
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
