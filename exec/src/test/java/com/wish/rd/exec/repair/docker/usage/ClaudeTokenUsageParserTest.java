package com.wish.rd.exec.repair.docker.usage;

import com.wish.rd.exec.repair.docker.usage.model.ClaudeTokenUsageSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeTokenUsageParserTest {

    private final ClaudeTokenUsageParser parser = new ClaudeTokenUsageParser();

    @Test
    void shouldAggregateUniqueAssistantUsageAndFinalCost() {
        ClaudeTokenUsageSnapshot snapshot = parser.parse("""
                {"type":"assistant","session_id":"session-1","message":{"id":"msg-1","usage":{"input_tokens":10,"output_tokens":20,"cache_creation_input_tokens":3,"cache_read_input_tokens":4}}}
                {"type":"assistant","session_id":"session-1","message":{"id":"msg-1","usage":{"input_tokens":999,"output_tokens":999}}}
                {"type":"assistant","session_id":"session-1","message":{"id":"msg-2","usage":{"input_tokens":5,"output_tokens":6,"cache_creation_input_tokens":7,"cache_read_input_tokens":8}}}
                {"type":"result","session_id":"session-1","total_cost":0.42}
                """);

        assertEquals(15L, snapshot.inputTokens());
        assertEquals(26L, snapshot.outputTokens());
        assertEquals(10L, snapshot.cacheCreationInputTokens());
        assertEquals(12L, snapshot.cacheReadInputTokens());
        assertEquals(63L, snapshot.totalTokens());
        assertEquals(new BigDecimal("0.42"), snapshot.estimatedCostUsd());
        assertEquals("session-1", snapshot.sessionId());
        assertEquals(2, snapshot.uniqueAssistantMessages());
        assertTrue(snapshot.finalized());
        assertTrue(snapshot.available());
    }

    @Test
    void shouldIgnoreDamagedLinesAndSupportAlternativeCostFields() {
        ClaudeTokenUsageSnapshot snapshot = parser.parse("""
                {"type":"assistant","message":{"id":"msg-1","usage":{"input_tokens":2,"output_tokens":3}}}
                {"type":"assistant","message":
                this is not json
                {"type":"result","cost_usd":"1.25"}
                """);

        assertEquals(2L, snapshot.inputTokens());
        assertEquals(3L, snapshot.outputTokens());
        assertEquals(0L, snapshot.cacheCreationInputTokens());
        assertEquals(0L, snapshot.cacheReadInputTokens());
        assertEquals(5L, snapshot.totalTokens());
        assertEquals(new BigDecimal("1.25"), snapshot.estimatedCostUsd());
        assertTrue(snapshot.finalized());
    }

    @Test
    void shouldExposeUnavailableSnapshotWhenNoUsageOrFinalResultExists() {
        ClaudeTokenUsageSnapshot snapshot = parser.parse("""
                {"type":"system","subtype":"init"}
                incomplete json
                """);

        assertEquals(0L, snapshot.totalTokens());
        assertFalse(snapshot.available());
        assertFalse(snapshot.finalized());
    }
}
