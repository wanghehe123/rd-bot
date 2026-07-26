package com.wish.rd.exec.repair.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** One accepted normalized event with the Java-assigned sequence. */
public record AgentExecutionEvent(
        long sequence,
        String containerName,
        JsonNode event
) {

    public AgentExecutionEvent {
        if (sequence <= 0L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        containerName = containerName == null ? "" : containerName.strip();
        event = Objects.requireNonNull(event, "event must not be null").deepCopy();
    }
}
