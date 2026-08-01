package com.wish.rd.rag.project.agent.model;

import java.util.Map;

/** Append-only agent state event. */
public record AgentStateEvent(
        long sequence,
        String eventType,
        String occurredAt,
        AgentStateAction action,
        Map<String, Object> payload
) {

    public AgentStateEvent {
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        eventType = requireText(eventType, "eventType");
        occurredAt = requireText(occurredAt, "occurredAt");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
