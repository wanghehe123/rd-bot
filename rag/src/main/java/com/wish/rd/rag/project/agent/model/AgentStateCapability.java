package com.wish.rd.rag.project.agent.model;

/** Declared runtime capability for agent state projection. */
public record AgentStateCapability(
        String name,
        boolean enabled,
        String reason
) {

    public AgentStateCapability {
        name = requireText(name, "name");
        reason = reason == null || reason.isBlank() ? null : reason.strip();
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
