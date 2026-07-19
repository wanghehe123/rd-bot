package com.wish.rd.exec.repair.docker.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.docker.usage.model.ClaudeTokenUsageSnapshot;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Tolerantly parses a Claude Code stream-json event file while it is still being appended.
 */
public final class ClaudeTokenUsageParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public ClaudeTokenUsageSnapshot parse(Path eventFile) {
        if (eventFile == null || !Files.isRegularFile(eventFile)) {
            return emptySnapshot();
        }
        try {
            return parse(Files.readString(eventFile, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            return emptySnapshot();
        }
    }

    public ClaudeTokenUsageSnapshot parse(String eventsJsonl) {
        long inputTokens = 0L;
        long outputTokens = 0L;
        long cacheCreationInputTokens = 0L;
        long cacheReadInputTokens = 0L;
        BigDecimal estimatedCostUsd = BigDecimal.ZERO;
        String sessionId = "";
        boolean finalized = false;
        Set<String> assistantMessageIds = new HashSet<>();

        if (eventsJsonl == null || eventsJsonl.isBlank()) {
            return emptySnapshot();
        }

        for (String line : eventsJsonl.split("\\R")) {
            JsonNode event = parseLine(line);
            if (event == null) {
                continue;
            }
            if (sessionId.isBlank()) {
                sessionId = text(event, "session_id");
            }
            if ("assistant".equals(text(event, "type"))) {
                JsonNode message = event.path("message");
                String messageId = text(message, "id");
                JsonNode usage = message.path("usage");
                if (!messageId.isBlank() && usage.isObject() && assistantMessageIds.add(messageId)) {
                    inputTokens += nonNegativeLong(usage, "input_tokens");
                    outputTokens += nonNegativeLong(usage, "output_tokens");
                    cacheCreationInputTokens += nonNegativeLong(usage, "cache_creation_input_tokens");
                    cacheReadInputTokens += nonNegativeLong(usage, "cache_read_input_tokens");
                }
                continue;
            }
            if ("result".equals(text(event, "type"))) {
                finalized = true;
                estimatedCostUsd = firstCost(event, estimatedCostUsd);
            }
        }

        long totalTokens = inputTokens + outputTokens + cacheCreationInputTokens + cacheReadInputTokens;
        return new ClaudeTokenUsageSnapshot(
                inputTokens,
                outputTokens,
                cacheCreationInputTokens,
                cacheReadInputTokens,
                totalTokens,
                estimatedCostUsd,
                sessionId,
                assistantMessageIds.size(),
                finalized
        );
    }

    private static ClaudeTokenUsageSnapshot emptySnapshot() {
        return new ClaudeTokenUsageSnapshot(0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, "", 0, false);
    }

    private static JsonNode parseLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(line);
            return node != null && node.isObject() ? node : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        return value != null && value.isTextual() ? value.textValue().trim() : "";
    }

    private static long nonNegativeLong(JsonNode usage, String fieldName) {
        JsonNode value = usage.get(fieldName);
        if (value == null) {
            return 0L;
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

    private static BigDecimal firstCost(JsonNode event, BigDecimal fallback) {
        for (String fieldName : new String[]{"total_cost_usd", "total_cost", "cost_usd"}) {
            JsonNode value = event.get(fieldName);
            if (value == null || value.isNull()) {
                continue;
            }
            try {
                BigDecimal cost = new BigDecimal(value.asText());
                if (cost.signum() >= 0) {
                    return cost;
                }
            } catch (NumberFormatException ignored) {
                // A malformed final cost must not make an otherwise useful stream unusable.
            }
        }
        return fallback;
    }
}
