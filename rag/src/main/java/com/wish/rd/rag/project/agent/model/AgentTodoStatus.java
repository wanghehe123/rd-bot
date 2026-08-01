package com.wish.rd.rag.project.agent.model;

/** TODO lifecycle status for agent state snapshots. */
public enum AgentTodoStatus {
    PENDING,
    IN_PROGRESS,
    BLOCKED,
    DONE,
    CANCELLED;

    /**
     * Returns whether a transition from {@code from} to {@code to} is allowed.
     */
    public static boolean isValidTransition(AgentTodoStatus from, AgentTodoStatus to) {
        if (from == null || to == null || from == to) {
            return from == to;
        }
        return switch (from) {
            case PENDING -> to == IN_PROGRESS || to == BLOCKED || to == CANCELLED;
            case IN_PROGRESS -> to == DONE || to == BLOCKED || to == CANCELLED;
            case BLOCKED -> to == IN_PROGRESS || to == CANCELLED;
            case DONE, CANCELLED -> false;
        };
    }
}
