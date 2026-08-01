package com.wish.rd.exec.repair.runtime.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.runtime.usage.model.AgentEventTokenUsageSnapshot;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Aggregates provider usage from normalized Pi {@code agent-events.jsonl} artifacts. */
public final class AgentEventTokenUsageParser {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String EVENT_PROTOCOL = "rd-agent-event/v1";

  public AgentEventTokenUsageSnapshot parse(Path eventFile) {
    if (eventFile == null || !Files.isRegularFile(eventFile)) {
      return unavailable();
    }
    try {
      return parse(Files.readString(eventFile, StandardCharsets.UTF_8));
    } catch (IOException ignored) {
      return unavailable();
    }
  }

  public AgentEventTokenUsageSnapshot parse(String eventsJsonl) {
    if (eventsJsonl == null || eventsJsonl.isBlank()) {
      return unavailable();
    }
    long inputTokens = 0L;
    long outputTokens = 0L;
    long cacheReadTokens = 0L;
    long cacheWriteTokens = 0L;
    BigDecimal estimatedCostUsd = BigDecimal.ZERO;
    boolean finalized = false;
    Set<Long> countedSequences = new HashSet<>();
    for (String line : eventsJsonl.split("\\R")) {
      JsonNode event = parseLine(line);
      if (event == null) {
        continue;
      }
      String eventType = text(event, "eventType");
      if ("AGENT_SETTLED".equals(eventType) || "RUNTIME_STOPPED".equals(eventType)) {
        finalized = true;
      }
      if (!"ASSISTANT_TEXT_COMPLETED".equals(eventType) && !"TURN_COMPLETED".equals(eventType)) {
        continue;
      }
      long sourceSequence = event.path("sourceSequence").asLong(-1L);
      if (sourceSequence < 0L || !countedSequences.add(sourceSequence)) {
        continue;
      }
      JsonNode usage = event.path("payload").path("usage");
      if (!usage.isObject() || usage.isEmpty()) {
        continue;
      }
      long input = nonNegativeLong(usage, "input");
      long output = nonNegativeLong(usage, "output");
      long cacheRead = nonNegativeLong(usage, "cacheRead");
      long cacheWrite = nonNegativeLong(usage, "cacheWrite");
      if (input == 0L && output == 0L && cacheRead == 0L && cacheWrite == 0L) {
        continue;
      }
      inputTokens += input;
      outputTokens += output;
      cacheReadTokens += cacheRead;
      cacheWriteTokens += cacheWrite;
      estimatedCostUsd = firstCost(usage.path("cost"), estimatedCostUsd);
    }
    if (countedSequences.isEmpty()) {
      return unavailable();
    }
    long totalTokens = inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens;
    return new AgentEventTokenUsageSnapshot(
        inputTokens,
        outputTokens,
        cacheReadTokens,
        cacheWriteTokens,
        totalTokens,
        estimatedCostUsd,
        countedSequences.size(),
        finalized,
        true
    );
  }

  private static AgentEventTokenUsageSnapshot unavailable() {
    return new AgentEventTokenUsageSnapshot(
        0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, 0, false, false
    );
  }

  private static JsonNode parseLine(String line) {
    if (line == null || line.isBlank()) {
      return null;
    }
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

  private static String text(JsonNode node, String fieldName) {
    JsonNode value = node == null ? null : node.get(fieldName);
    return value != null && value.isTextual() ? value.textValue().trim() : "";
  }

  private static long nonNegativeLong(JsonNode usage, String fieldName) {
    JsonNode value = usage.get(fieldName);
    if (value == null || value.isNull()) {
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

  private static BigDecimal firstCost(JsonNode cost, BigDecimal fallback) {
    if (cost == null || !cost.isObject()) {
      return fallback;
    }
    for (String fieldName : new String[] {"total", "input", "output"}) {
      JsonNode value = cost.get(fieldName);
      if (value == null || value.isNull()) {
        continue;
      }
      try {
        BigDecimal parsed = new BigDecimal(value.asText());
        if (parsed.signum() >= 0) {
          return parsed;
        }
      } catch (NumberFormatException ignored) {
        // Malformed provider cost metadata must not invalidate usage aggregation.
      }
    }
    return fallback;
  }
}
