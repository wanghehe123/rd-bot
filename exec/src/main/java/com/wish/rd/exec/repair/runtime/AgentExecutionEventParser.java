package com.wish.rd.exec.repair.runtime;

import com.wish.rd.exec.repair.runtime.model.AgentExecutionTraceSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/** Parses the bounded normalized Pi event artifact without exposing raw session data. */
public final class AgentExecutionEventParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PROTOCOL = "rd-agent-event/v1";

    private AgentExecutionEventParser() {
    }

    public static AgentExecutionTraceSnapshot parseJsonl(
            String jsonl,
            String taskId,
            String stageRunId,
            long afterSequence,
            int requestedLimit
    ) {
        String expectedTaskId = normalize(taskId);
        String expectedStageRunId = normalize(stageRunId);
        if (expectedTaskId.isBlank() || expectedStageRunId.isBlank()) {
            return AgentExecutionTraceSnapshot.unavailable("ARCHIVED");
        }
        int limit = Math.max(1, Math.min(200, requestedLimit <= 0 ? 100 : requestedLimit));
        long cursor = Math.max(0L, afterSequence);
        List<JsonNode> accepted = new ArrayList<>();
        long lastSequence = 0L;
        boolean finalized = false;
        boolean truncated = false;
        String[] lines = (jsonl == null ? "" : jsonl).split("\\R");
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            final JsonNode parsed;
            try {
                parsed = OBJECT_MAPPER.readTree(line);
            } catch (Exception exception) {
                continue;
            }
            if (parsed == null || !parsed.isObject()
                    || !PROTOCOL.equals(parsed.path("protocol").asText(""))
                    || !expectedTaskId.equals(parsed.path("taskId").asText(""))
                    || !expectedStageRunId.equals(parsed.path("stageRunId").asText(""))) {
                continue;
            }
            long sourceSequence = parsed.path("sourceSequence").asLong(-1L);
            if (sourceSequence <= lastSequence) continue;
            lastSequence = sourceSequence;
            String type = parsed.path("eventType").asText("");
            if (type.isBlank()) continue;
            if ("AGENT_SETTLED".equals(type) || "RUNTIME_STOPPED".equals(type)) {
                finalized = true;
            }
            if (sourceSequence <= cursor || accepted.size() >= limit) continue;
            ObjectNode copy = (ObjectNode) parsed.deepCopy();
            copy.put("sequence", sourceSequence);
            accepted.add(copy);
        }
        long nextSequence = accepted.isEmpty()
                ? cursor
                : accepted.getLast().path("sequence").asLong(cursor);
        boolean hasMore = lastSequence > nextSequence;
        if (jsonl != null && jsonl.contains("[truncated]")) {
            truncated = true;
        }
        return new AgentExecutionTraceSnapshot(
                AgentExecutionTraceSnapshot.VERSION,
                "ARCHIVED",
                lastSequence > 0L,
                finalized,
                truncated,
                hasMore,
                nextSequence,
                accepted
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
