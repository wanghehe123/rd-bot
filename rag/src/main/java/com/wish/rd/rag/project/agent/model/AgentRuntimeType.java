package com.wish.rd.rag.project.agent.model;

import java.util.Locale;

/** Runtime strategies available to the requirement-delivery executor router. */
public enum AgentRuntimeType {
    PI,
    CLAUDE_CODE,
    MODEL_ONLY;

    public static AgentRuntimeType parse(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("runtimeType must not be blank");
        }
        try {
            return valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported runtimeType: " + value, exception);
        }
    }
}
