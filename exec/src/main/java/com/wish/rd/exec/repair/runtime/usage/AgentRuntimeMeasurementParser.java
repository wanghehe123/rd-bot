package com.wish.rd.exec.repair.runtime.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.runtime.usage.model.AgentEventTokenUsageSnapshot;
import com.wish.rd.exec.repair.runtime.usage.model.AgentRuntimeMeasurementSummary;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime-neutral parser for redacted {@code rd-agent-event/v1} streams.
 *
 * <p>TTFT requires a request/agent start and the first non-thinking assistant
 * text. {@code PROVIDER_RESPONDED} is a separate first-response metric.
 */
public final class AgentRuntimeMeasurementParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String EVENT_PROTOCOL = "rd-agent-event/v1";
    private static final AgentEventTokenUsageParser USAGE_PARSER = new AgentEventTokenUsageParser();

    /**
     * Parses JSONL using unknown runtime metadata.
     *
     * @param eventsJsonl normalized events
     * @return summary; never null
     */
    public AgentRuntimeMeasurementSummary parse(String eventsJsonl) {
        return parse(eventsJsonl, "unknown", "OTHER", "");
    }

    /**
     * Parses a JSONL file.
     *
     * @param eventFile event file
     * @return summary; never null
     */
    public AgentRuntimeMeasurementSummary parse(Path eventFile) {
        return parse(eventFile, "unknown", "OTHER", "");
    }

    /**
     * Parses a JSONL file and stamps runtime metadata.
     *
     * @param eventFile event file
     * @param runtime runtime allowlist value
     * @param provider provider id
     * @param modelAlias model alias
     * @return summary; never null
     */
    public AgentRuntimeMeasurementSummary parse(
            Path eventFile,
            String runtime,
            String provider,
            String modelAlias
    ) {
        if (eventFile == null || !Files.isRegularFile(eventFile)) {
            return AgentRuntimeMeasurementSummary.unavailable("");
        }
        try {
            return parse(Files.readString(eventFile, StandardCharsets.UTF_8), runtime, provider, modelAlias);
        } catch (IOException ignored) {
            return AgentRuntimeMeasurementSummary.unavailable("PARSE_ERROR");
        }
    }

    /**
     * Parses JSONL and stamps configured runtime/provider/model alias.
     *
     * @param eventsJsonl normalized events
     * @param runtime runtime allowlist value
     * @param provider provider id
     * @param modelAlias model alias
     * @return summary; never null
     */
    public AgentRuntimeMeasurementSummary parse(
            String eventsJsonl,
            String runtime,
            String provider,
            String modelAlias
    ) {
        try {
            return parseInternal(eventsJsonl, runtime, provider, modelAlias);
        } catch (RuntimeException ignored) {
            return AgentRuntimeMeasurementSummary.unavailable("PARSE_ERROR");
        }
    }

    /**
     * Deduplicates provider-attempt usage by immutable attemptId and sums distinct attempts.
     *
     * @param providerAttemptsJson existing compatibility JSON array
     * @param runtime runtime allowlist value
     * @return aggregated usage summary
     */
    public AgentRuntimeMeasurementSummary aggregateProviderAttempts(String providerAttemptsJson, String runtime) {
        if (providerAttemptsJson == null || providerAttemptsJson.isBlank()) {
            return AgentRuntimeMeasurementSummary.unavailable("");
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(providerAttemptsJson);
            if (root == null || !root.isArray()) {
                return AgentRuntimeMeasurementSummary.unavailable("PARSE_ERROR");
            }
            Map<String, JsonNode> unique = new LinkedHashMap<>();
            int index = 0;
            for (JsonNode attempt : root) {
                if (attempt == null || !attempt.isObject()) {
                    continue;
                }
                String attemptId = text(attempt, "attemptId");
                if (attemptId.isBlank()) {
                    attemptId = text(attempt, "provider") + ":" + attempt.path("attempt").asInt(++index);
                }
                unique.putIfAbsent(attemptId, attempt);
            }
            long input = 0L;
            long output = 0L;
            long cacheRead = 0L;
            long cacheWrite = 0L;
            BigDecimal cost = BigDecimal.ZERO;
            boolean any = false;
            String provider = "OTHER";
            for (JsonNode attempt : unique.values()) {
                if (!usageAvailable(attempt)) {
                    continue;
                }
                any = true;
                input += nonNegative(attempt, "inputTokens");
                output += nonNegative(attempt, "outputTokens");
                cacheRead += nonNegative(attempt, "cacheReadInputTokens", "cacheReadTokens");
                cacheWrite += nonNegative(attempt, "cacheCreationInputTokens", "cacheWriteTokens");
                cost = cost.add(decimal(attempt.path("estimatedCostUsd")));
                String candidate = text(attempt, "provider");
                if (!candidate.isBlank()) {
                    provider = candidate;
                }
            }
            if (!any) {
                return AgentRuntimeMeasurementSummary.unavailable("");
            }
            long total = input + output + cacheRead + cacheWrite;
            return new AgentRuntimeMeasurementSummary(
                    AgentRuntimeMeasurementSummary.SCHEMA_VERSION,
                    "", "", "", runtime, provider, "",
                    0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                    false, false, true,
                    input, output, cacheRead, cacheWrite, total,
                    cost, "USD", "", unique.size(), 0, 0L, true, true
            );
        } catch (IOException ignored) {
            return AgentRuntimeMeasurementSummary.unavailable("PARSE_ERROR");
        }
    }

    private AgentRuntimeMeasurementSummary parseInternal(
            String eventsJsonl,
            String runtime,
            String provider,
            String modelAlias
    ) {
        if (eventsJsonl == null || eventsJsonl.isBlank()) {
            return AgentRuntimeMeasurementSummary.unavailable("");
        }
        List<JsonNode> events = new ArrayList<>();
        Set<Long> seenSequences = new HashSet<>();
        long dropped = 0L;
        boolean sawMalformed = false;
        for (String line : eventsJsonl.split("\\R")) {
            if (line == null || line.isBlank()) {
                continue;
            }
            JsonNode event = parseLine(line);
            if (event == null) {
                dropped++;
                sawMalformed = true;
                continue;
            }
            long sequence = event.path("sourceSequence").asLong(Long.MIN_VALUE);
            if (sequence != Long.MIN_VALUE && !seenSequences.add(sequence)) {
                dropped++;
                continue;
            }
            events.add(event);
        }
        if (events.isEmpty()) {
            return AgentRuntimeMeasurementSummary.unavailable(sawMalformed ? "PARSE_ERROR" : "");
        }
        events.sort(Comparator
                .comparingLong((JsonNode event) -> occurredAtMillis(event))
                .thenComparingLong(event -> event.path("sourceSequence").asLong(0L)));

        String taskId = "";
        String stageRunId = "";
        String role = "";
        long requestStart = 0L;
        long agentStart = 0L;
        long firstProvider = 0L;
        long firstText = 0L;
        long finished = 0L;
        boolean finalized = false;
        int retries = 0;
        Map<String, Long> toolStarted = new HashMap<>();
        long toolDuration = 0L;
        String parseError = "";
        for (JsonNode event : events) {
            if (taskId.isBlank()) {
                taskId = text(event, "taskId");
            }
            if (stageRunId.isBlank()) {
                stageRunId = text(event, "stageRunId");
            }
            if (role.isBlank()) {
                role = text(event, "role");
            }
            String type = text(event, "eventType");
            long at = occurredAtMillis(event);
            if ("PROVIDER_REQUESTED".equals(type) && requestStart == 0L) {
                requestStart = at;
            } else if ("AGENT_STARTED".equals(type) && agentStart == 0L) {
                agentStart = at;
            } else if ("PROVIDER_RESPONDED".equals(type) && firstProvider == 0L) {
                firstProvider = at;
            } else if ("ASSISTANT_TEXT_DELTA".equals(type) && firstText == 0L && isVisibleText(event)) {
                firstText = at;
            } else if ("PROVIDER_RETRYING".equals(type)) {
                retries++;
            } else if ("TOOL_STARTED".equals(type)) {
                String toolId = toolId(event);
                if (!toolId.isBlank() && at > 0L) {
                    toolStarted.putIfAbsent(toolId, at);
                }
            } else if ("TOOL_COMPLETED".equals(type)) {
                String toolId = toolId(event);
                Long startedAt = toolStarted.remove(toolId);
                if (startedAt != null && at >= startedAt) {
                    toolDuration += at - startedAt;
                }
            } else if ("AGENT_SETTLED".equals(type) || "RUNTIME_STOPPED".equals(type)) {
                finalized = true;
                if (finished == 0L) {
                    finished = at;
                }
            }
        }
        long ttftStart = agentStart > 0L ? agentStart : requestStart;
        long responseStart = requestStart > 0L ? requestStart : agentStart;
        boolean firstTokenAvailable = false;
        long firstTokenDuration = 0L;
        if (ttftStart > 0L && firstText > 0L) {
            if (firstText >= ttftStart) {
                firstTokenAvailable = true;
                firstTokenDuration = firstText - ttftStart;
            } else {
                parseError = "NEGATIVE_DURATION";
            }
        }
        boolean firstResponseAvailable = responseStart > 0L && firstProvider > 0L && firstProvider >= responseStart;
        long firstResponseDuration = firstResponseAvailable ? firstProvider - responseStart : 0L;
        long totalDuration = ttftStart > 0L && finished >= ttftStart ? finished - ttftStart : 0L;
        AgentEventTokenUsageSnapshot usage = USAGE_PARSER.parse(eventsJsonl);
        return new AgentRuntimeMeasurementSummary(
                AgentRuntimeMeasurementSummary.SCHEMA_VERSION,
                taskId,
                stageRunId,
                role,
                runtime,
                provider,
                modelAlias,
                agentStart,
                firstProvider,
                firstText,
                finished,
                totalDuration,
                firstTokenDuration,
                firstResponseDuration,
                toolDuration,
                firstTokenAvailable,
                firstResponseAvailable,
                usage.available(),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheReadTokens(),
                usage.cacheWriteTokens(),
                usage.totalTokens(),
                usage.estimatedCostUsd(),
                "USD",
                parseError,
                usage.usageEventCount(),
                retries,
                dropped,
                finalized || usage.finalized(),
                true
        );
    }

    private static boolean isVisibleText(JsonNode event) {
        JsonNode payload = event.path("payload");
        if (payload.path("thinking").asBoolean(false)) {
            return false;
        }
        String kind = text(payload, "textKind");
        if ("thinking".equalsIgnoreCase(kind) || "thinking_delta".equalsIgnoreCase(kind)) {
            return false;
        }
        String delta = text(payload, "delta");
        String body = text(payload, "text");
        return !delta.isBlank() || !body.isBlank();
    }

    private static String toolId(JsonNode event) {
        JsonNode payload = event.path("payload");
        String id = text(payload, "toolCallId");
        return id.isBlank() ? text(payload, "toolName") : id;
    }

    private static JsonNode parseLine(String line) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(line);
            if (node == null || !node.isObject() || !EVENT_PROTOCOL.equals(text(node, "protocol"))) {
                return null;
            }
            return node;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static long occurredAtMillis(JsonNode event) {
        String raw = text(event, "occurredAt");
        if (raw.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(raw).toEpochMilli();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static boolean usageAvailable(JsonNode attempt) {
        if (attempt.path("tokenUsageAvailable").asBoolean(false)) {
            return true;
        }
        return nonNegative(attempt, "inputTokens") > 0L
                || nonNegative(attempt, "outputTokens") > 0L
                || nonNegative(attempt, "totalTokens") > 0L;
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        return value != null && value.isTextual() ? value.textValue().strip() : "";
    }

    private static long nonNegative(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value == null || value.isNull() || value.isMissingNode()) {
                continue;
            }
            if (value.canConvertToLong()) {
                return Math.max(0L, value.longValue());
            }
            try {
                return Math.max(0L, Long.parseLong(value.asText()));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static BigDecimal decimal(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal parsed = new BigDecimal(value.asText());
            return parsed.signum() < 0 ? BigDecimal.ZERO : parsed;
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }
}
