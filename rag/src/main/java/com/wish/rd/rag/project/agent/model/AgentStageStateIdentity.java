package com.wish.rd.rag.project.agent.model;

/** Immutable identity shared by state and context-injection projections. */
public record AgentStageStateIdentity(String taskId, String stageRunId, String role, int attemptNo) {

    public AgentStageStateIdentity {
        taskId = requireText(taskId, "taskId");
        stageRunId = requireText(stageRunId, "stageRunId");
        role = requireText(role, "role");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
