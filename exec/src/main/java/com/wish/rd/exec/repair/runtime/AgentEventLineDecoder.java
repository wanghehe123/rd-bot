package com.wish.rd.exec.repair.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.function.Consumer;

/** Incremental LF JSONL decoder for the normalized rd-agent-event/v1 stream. */
public final class AgentEventLineDecoder {

    public static final int MAX_LINE_BYTES = 256 * 1024;
    private static final String PROTOCOL = "rd-agent-event/v1";

    private final ObjectMapper objectMapper;
    private final Consumer<JsonNode> consumer;
    private final StringBuilder pending = new StringBuilder();
    private long lastSourceSequence;

    public AgentEventLineDecoder(ObjectMapper objectMapper, Consumer<JsonNode> consumer) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.consumer = Objects.requireNonNull(consumer, "consumer must not be null");
    }

    public void accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        pending.append(chunk);
        int newline;
        while ((newline = pending.indexOf("\n")) >= 0) {
            String line = pending.substring(0, newline);
            pending.delete(0, newline + 1);
            decodeLine(line);
        }
        if (pending.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
            throw new IllegalArgumentException("agent event line exceeds maximum size");
        }
    }

    public void finish() {
        if (!pending.isEmpty()) {
            decodeLine(pending.toString());
            pending.setLength(0);
        }
    }

    public long lastSourceSequence() {
        return lastSourceSequence;
    }

    private void decodeLine(String line) {
        String normalized = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        if (normalized.isBlank()) return;
        if (normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
            throw new IllegalArgumentException("agent event line exceeds maximum size");
        }
        final JsonNode event;
        try {
            event = objectMapper.readTree(normalized);
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid agent event JSON", exception);
        }
        if (event == null || !event.isObject() || !PROTOCOL.equals(event.path("protocol").asText(""))) {
            throw new IllegalArgumentException("agent event protocol is invalid");
        }
        if (event.path("eventType").asText("").isBlank()) {
            throw new IllegalArgumentException("agent event type is blank");
        }
        long sequence = event.path("sourceSequence").asLong(-1L);
        if (sequence <= lastSourceSequence) {
            throw new IllegalArgumentException("agent event sourceSequence is not strictly increasing");
        }
        lastSourceSequence = sequence;
        consumer.accept(event);
    }
}
