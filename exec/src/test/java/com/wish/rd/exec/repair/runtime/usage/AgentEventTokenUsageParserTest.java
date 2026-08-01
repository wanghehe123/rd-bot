package com.wish.rd.exec.repair.runtime.usage;

import com.wish.rd.exec.repair.runtime.usage.model.AgentEventTokenUsageSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEventTokenUsageParserTest {

  private final AgentEventTokenUsageParser parser = new AgentEventTokenUsageParser();

  @Test
  void shouldAggregateUsageFromNormalizedPiEvents() {
    String jsonl = """
        {"protocol":"rd-agent-event/v1","eventType":"ASSISTANT_TEXT_COMPLETED","sourceSequence":1,"payload":{"usage":{"input":11,"output":12,"cacheRead":13,"cacheWrite":14,"cost":{"total":0.25}}}}
        {"protocol":"rd-agent-event/v1","eventType":"AGENT_SETTLED","sourceSequence":2}
        """;

    AgentEventTokenUsageSnapshot snapshot = parser.parse(jsonl);

    assertTrue(snapshot.available());
    assertTrue(snapshot.finalized());
    assertEquals(11L, snapshot.inputTokens());
    assertEquals(12L, snapshot.outputTokens());
    assertEquals(13L, snapshot.cacheReadTokens());
    assertEquals(14L, snapshot.cacheWriteTokens());
    assertEquals(50L, snapshot.totalTokens());
    assertEquals(1, snapshot.usageEventCount());
  }

  @Test
  void shouldCountUsageOnlyOnceWhenAssistantAndTurnCompletedShareSameTurn() {
    String jsonl = """
        {"protocol":"rd-agent-event/v1","eventType":"TURN_STARTED","sourceSequence":4}
        {"protocol":"rd-agent-event/v1","eventType":"ASSISTANT_TEXT_COMPLETED","sourceSequence":262,"payload":{"usage":{"input":4595,"output":298,"cacheRead":0,"cacheWrite":0,"cost":{"total":0.25}}}}
        {"protocol":"rd-agent-event/v1","eventType":"TURN_COMPLETED","sourceSequence":277,"payload":{"usage":{"input":4595,"output":298,"cacheRead":0,"cacheWrite":0,"cost":{"total":0.25}}}}
        {"protocol":"rd-agent-event/v1","eventType":"AGENT_SETTLED","sourceSequence":278}
        """;

    AgentEventTokenUsageSnapshot snapshot = parser.parse(jsonl);

    assertTrue(snapshot.available());
    assertTrue(snapshot.finalized());
    assertEquals(4595L, snapshot.inputTokens());
    assertEquals(298L, snapshot.outputTokens());
    assertEquals(4893L, snapshot.totalTokens());
    assertEquals(1, snapshot.usageEventCount());
  }

  @Test
  void shouldPreferTurnCompletedUsageOverAssistantFallbackWithinSameTurn() {
    String jsonl = """
        {"protocol":"rd-agent-event/v1","eventType":"TURN_STARTED","sourceSequence":1}
        {"protocol":"rd-agent-event/v1","eventType":"ASSISTANT_TEXT_COMPLETED","sourceSequence":2,"payload":{"usage":{"input":10,"output":20,"cacheRead":0,"cacheWrite":0}}}
        {"protocol":"rd-agent-event/v1","eventType":"TURN_COMPLETED","sourceSequence":3,"payload":{"usage":{"input":30,"output":40,"cacheRead":0,"cacheWrite":0}}}
        """;

    AgentEventTokenUsageSnapshot snapshot = parser.parse(jsonl);

    assertTrue(snapshot.available());
    assertEquals(30L, snapshot.inputTokens());
    assertEquals(40L, snapshot.outputTokens());
    assertEquals(1, snapshot.usageEventCount());
  }

  @Test
  void shouldFallbackToAssistantUsageWhenTurnCompletedIsMissingForTurn() {
    String jsonl = """
        {"protocol":"rd-agent-event/v1","eventType":"TURN_STARTED","sourceSequence":1}
        {"protocol":"rd-agent-event/v1","eventType":"ASSISTANT_TEXT_COMPLETED","sourceSequence":2,"payload":{"usage":{"input":15,"output":25,"cacheRead":1,"cacheWrite":2}}}
        {"protocol":"rd-agent-event/v1","eventType":"RUNTIME_STOPPED","sourceSequence":3}
        """;

    AgentEventTokenUsageSnapshot snapshot = parser.parse(jsonl);

    assertTrue(snapshot.available());
    assertTrue(snapshot.finalized());
    assertEquals(15L, snapshot.inputTokens());
    assertEquals(25L, snapshot.outputTokens());
    assertEquals(1L, snapshot.cacheReadTokens());
    assertEquals(2L, snapshot.cacheWriteTokens());
    assertEquals(43L, snapshot.totalTokens());
    assertEquals(1, snapshot.usageEventCount());
  }

  @Test
  void shouldRemainUnavailableWhenUsageIsMissing() {
    String jsonl = """
        {"protocol":"rd-agent-event/v1","eventType":"RUNTIME_READY","sourceSequence":1}
        {"protocol":"rd-agent-event/v1","eventType":"AGENT_SETTLED","sourceSequence":2}
        """;

    AgentEventTokenUsageSnapshot snapshot = parser.parse(jsonl);

    assertFalse(snapshot.available());
    assertEquals(0L, snapshot.totalTokens());
  }
}
