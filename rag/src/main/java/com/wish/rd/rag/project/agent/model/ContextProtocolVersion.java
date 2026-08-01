package com.wish.rd.rag.project.agent.model;

/** Frozen result context protocol version. */
public enum ContextProtocolVersion {
    LEGACY_ENVIRONMENT_NOTES,
    FACTS_V1;

    public static ContextProtocolVersion parse(String value) {
        if (value == null || value.isBlank()) {
            return LEGACY_ENVIRONMENT_NOTES;
        }
        try {
            return ContextProtocolVersion.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return LEGACY_ENVIRONMENT_NOTES;
        }
    }

    public boolean matches(String value) {
        return this == parse(value);
    }
}
