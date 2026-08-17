package com.wish.rd.rag.project.agent.model;

import java.util.Locale;

/** Whether a strategy slot uses the host default image or an uploaded Dockerfile. */
public enum AgentStrategyImageMode {
    LOCAL_DEFAULT,
    CUSTOM;

    /** Parses a stored image-mode token. */
    public static AgentStrategyImageMode parse(String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return LOCAL_DEFAULT;
        }
        return valueOf(normalized);
    }
}
