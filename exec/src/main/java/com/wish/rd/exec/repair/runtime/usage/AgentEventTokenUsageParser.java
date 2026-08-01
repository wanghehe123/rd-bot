package com.wish.rd.exec.repair.runtime.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.runtime.usage.model.AgentEventTokenUsageSnapshot;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
    boolean finalized = false;
    int turnIndex = -1;
    Map<Integer, JsonNode> turnCompletedUsage = new HashMap<>();
    Map<Integer, JsonNode> assistantUsage = new HashMap<>();
    Map<Long, JsonNode> orphanUsage = new HashMap<>();
    for (String line : eventsJsonl.split("\\R")) {
      JsonNode event = parseLine(line);
      if (event == null) {
        continue;
      }
      String eventType = text(event, "eventType");
      if ("AGENT_SETTLED".equals(eventType) || "RUNTIME_STOPPED".equals(eventType)) {
        finalized = true;
      }
      if ("TURN_STARTED".equals(eventType)) {
        turnIndex++;
        continue;
      }
      if (!"ASSISTANT_TEXT_COMPLETED".equals(eventType) && !"TURN_COMPLETED".equals(eventType)) {
        continue;
      }
      JsonNode usage = event.path("payload").path("usage");
      if (!hasCountableUsage(usage)) {
        continue;
      }
      if ("TURN_COMPLETED".equals(eventType)) {
        if (turnIndex >= 0) {
          turnCompletedUsage.put(turnIndex, usage);
        } else {
          orphanUsage.put(event.path("sourceSequence").asLong(-1L), usage);
        }
        continue;
      }
      if (turnIndex >= 0) {
        assistantUsage.put(turnIndex, usage);
      } else {
        orphanUsage.put(event.path("sourceSequence").asLong(-1L), usage);
      }
    }
    Set<Integer> turnIndexes = new TreeSet<>();
    turnIndexes.addAll(turnCompletedUsage.keySet());
    turnIndexes.addAll(assistantUsage.keySet());
    long inputTokens = 0L;
    long outputTokens = 0L;
    long cacheReadTokens = 0L;
    long cacheWriteTokens = 0L;
    BigDecimal estimatedCostUsd = BigDecimal.ZERO;
    int usageEventCount = 0;
    for (int turn : turnIndexes) {
      JsonNode usage = turnCompletedUsage.getOrDefault(turn, assistantUsage.get(turn));
      if (!hasCountableUsage(usage)) {
        continue;
      }
      inputTokens += nonNegativeLong(usage, "input");
      outputTokens += nonNegativeLong(usage, "output");
      cacheReadTokens += nonNegativeLong(usage, "cacheRead");
      cacheWriteTokens += nonNegativeLong(usage, "cacheWrite");
      estimatedCostUsd = firstCost(usage.path("cost"), estimatedCostUsd);
      usageEventCount++;
    }
    Set<Long> countedOrphans = new HashSet<>();
    for (Map.Entry<Long, JsonNode> entry : orphanUsage.entrySet()) {
      long sourceSequence = entry.getKey();
      if (sourceSequence < 0L || !countedOrphans.add(sourceSequence)) {
        continue;
      }
      JsonNode usage = entry.getValue();
      if (!hasCountableUsage(usage)) {
        continue;
      }
      inputTokens += nonNegativeLong(usage, "input");
      outputTokens += nonNegativeLong(usage, "output");
      cacheReadTokens += nonNegativeLong(usage, "cacheRead");
      cacheWriteTokens += nonNegativeLong(usage, "cacheWrite");
      estimatedCostUsd = firstCost(usage.path("cost"), estimatedCostUsd);
      usageEventCount++;
    }
    if (usageEventCount == 0) {
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
        usageEventCount,
        finalized,
        true
    );
  }

  private static AgentEventTokenUsageSnapshot unavailable() {
    return new AgentEventTokenUsageSnapshot(
        0L, 0L, 0L, 0L, 0L, BigDecimal.ZERO, 0, false, false
    );
  }

  private static boolean hasCountableUsage(JsonNode usage) {
    if (usage == null || !usage.isObject() || usage.isEmpty()) {
      return false;
    }
    return nonNegativeLong(usage, "input") > 0L
        || nonNegativeLong(usage, "output") > 0L
        || nonNegativeLong(usage, "cacheRead") > 0L
        || nonNegativeLong(usage, "cacheWrite") > 0L;
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
