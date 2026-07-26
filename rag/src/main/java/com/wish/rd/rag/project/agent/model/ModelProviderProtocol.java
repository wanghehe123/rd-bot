package com.wish.rd.rag.project.agent.model;

import java.util.Locale;

/** Protocol identifiers shared by Claude, Pi, and model-only adapters. */
public enum ModelProviderProtocol {
    ANTHROPIC_COMPATIBLE,
    ANTHROPIC_MESSAGES,
    OPENAI_CHAT_COMPLETIONS,
    OPENAI_COMPLETIONS,
    OPENAI_RESPONSES,
    GOOGLE_GENERATIVE_AI;

    public static ModelProviderProtocol parse(String value) {
        String normalized = value == null ? "" : value.strip()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_');
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("provider protocol must not be blank");
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported provider protocol: " + value, exception);
        }
    }
}
