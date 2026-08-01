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
