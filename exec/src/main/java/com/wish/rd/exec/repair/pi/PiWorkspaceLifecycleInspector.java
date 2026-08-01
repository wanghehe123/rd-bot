package com.wish.rd.exec.repair.pi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Inspects normalized Pi {@code agent-events.jsonl} artifacts for the required
 * {@code RESULT_SUBMITTED -> AGENT_SETTLED} lifecycle.
 */
public final class PiWorkspaceLifecycleInspector {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private PiWorkspaceLifecycleInspector() {
    }

    /**
     * @return lifecycle snapshot when {@code agent-events.jsonl} exists; otherwise empty
     */
    public static LifecycleSnapshot inspect(Path eventsFile, String taskId, String stageRunId, String role)
            throws IOException {
        if (eventsFile == null || !Files.isRegularFile(eventsFile)) {
            return LifecycleSnapshot.incomplete();
        }
        boolean resultSubmitted = false;
        boolean agentSettled = false;
        boolean resultSubmittedBeforeSettled = true;
        List<String> eventTypes = new ArrayList<>();
        for (String line : Files.readAllLines(eventsFile, StandardCharsets.UTF_8)) {
            String normalized = line == null ? "" : line.strip();
            if (normalized.isEmpty()) {
                continue;
            }
            JsonNode event = OBJECT_MAPPER.readTree(normalized);
            if (!matchesIdentity(event, taskId, stageRunId, role)) {
                continue;
            }
            String type = text(event.path("eventType"));
            if (type.isBlank()) {
                continue;
            }
            eventTypes.add(type);
            if ("AGENT_SETTLED".equals(type)) {
                if (!resultSubmitted) {
                    resultSubmittedBeforeSettled = false;
                }
                agentSettled = true;
            }
            if ("RESULT_SUBMITTED".equals(type)) {
                resultSubmitted = true;
            }
        }
        if (!resultSubmitted || !agentSettled || !resultSubmittedBeforeSettled) {
            return LifecycleSnapshot.incomplete();
        }
        return new LifecycleSnapshot(true, List.copyOf(eventTypes));
    }

    private static boolean matchesIdentity(JsonNode event, String taskId, String stageRunId, String role) {
        return taskId.equals(text(event.path("taskId")))
                && stageRunId.equals(text(event.path("stageRunId")))
                && role.equalsIgnoreCase(text(event.path("role")));
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").strip();
    }

    /**
     * Lifecycle inspection result for a workspace output directory.
     */
    public record LifecycleSnapshot(boolean complete, List<String> eventTypes) {

        public LifecycleSnapshot {
            eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        }

        private static LifecycleSnapshot incomplete() {
            return new LifecycleSnapshot(false, List.of());
        }
    }
}
