package com.wish.rd.exec.repair.runtime.usage;

import com.wish.rd.exec.repair.runtime.usage.model.AgentRuntimeMeasurementSummary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeMeasurementParserTest {

    private final AgentRuntimeMeasurementParser parser = new AgentRuntimeMeasurementParser();

    @Test
    void firstTokenUsesAgentStartAndFirstNonThinkingTextNotProviderResponse() {
        String jsonl = """
                {"protocol":"rd-agent-event/v1","eventType":"AGENT_STARTED","sourceSequence":1,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT","occurredAt":"2026-08-14T01:00:00Z","payload":{}}
                {"protocol":"rd-agent-event/v1","eventType":"PROVIDER_REQUESTED","sourceSequence":2,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT","occurredAt":"2026-08-14T01:00:01Z","payload":{}}
                {"protocol":"rd-agent-event/v1","eventType":"PROVIDER_RESPONDED","sourceSequence":3,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT","occurredAt":"2026-08-14T01:00:02Z","payload":{}}
                {"protocol":"rd-agent-event/v1","eventType":"ASSISTANT_TEXT_DELTA","sourceSequence":4,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT","occurredAt":"2026-08-14T01:00:01.500Z","payload":{"thinking":true,"delta":"hidden"}}
                {"protocol":"rd-agent-event/v1","eventType":"ASSISTANT_TEXT_DELTA","sourceSequence":5,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT","occurredAt":"2026-08-14T01:00:03Z","payload":{"delta":"Hello"}}
                {"protocol":"rd-agent-event/v1","eventType":"AGENT_SETTLED","sourceSequence":6,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT","occurredAt":"2026-08-14T01:00:10Z","payload":{}}
                """;

        AgentRuntimeMeasurementSummary summary = parser.parse(jsonl, "pi", "anthropic", "claude-alias");

        assertTrue(summary.available());
        assertTrue(summary.firstTokenAvailable());
        assertTrue(summary.firstProviderResponseAvailable());
        assertEquals(3000L, summary.firstTokenDurationMillis());
        assertEquals(1000L, summary.firstProviderResponseDurationMillis());
        assertEquals(10_000L, summary.totalDurationMillis());
        assertEquals("pi", summary.runtime());
        assertEquals("anthropic", summary.provider());
        assertFalse(summary.firstTokenDurationMillis() == summary.firstProviderResponseDurationMillis());
    }

    @Test
    void missingFirstTokenLeavesTtftUnavailableWhileUsageAndTotalRemain() {
        String jsonl = """
                {"protocol":"rd-agent-event/v1","eventType":"AGENT_STARTED","sourceSequence":1,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT","occurredAt":"2026-08-14T01:00:00Z","payload":{}}
                {"protocol":"rd-agent-event/v1","eventType":"PROVIDER_RESPONDED","sourceSequence":2,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT","occurredAt":"2026-08-14T01:00:02Z","payload":{}}
                {"protocol":"rd-agent-event/v1","eventType":"ASSISTANT_TEXT_COMPLETED","sourceSequence":3,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT","occurredAt":"2026-08-14T01:00:08Z","payload":{"usage":{"input":10,"output":5,"cacheRead":1,"cacheWrite":2,"cost":{"total":0.11}}}}
                {"protocol":"rd-agent-event/v1","eventType":"AGENT_SETTLED","sourceSequence":4,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT","occurredAt":"2026-08-14T01:00:08Z","payload":{}}
                """;

        AgentRuntimeMeasurementSummary summary = parser.parse(jsonl, "pi", "anthropic", "claude-alias");

        assertTrue(summary.available());
        assertFalse(summary.firstTokenAvailable());
        assertEquals(0L, summary.firstTokenDurationMillis());
        assertTrue(summary.firstProviderResponseAvailable());
        assertEquals(2000L, summary.firstProviderResponseDurationMillis());
        assertTrue(summary.usageAvailable());
        assertEquals(18L, summary.totalTokens());
        assertTrue(summary.finalized());
        assertEquals(8000L, summary.totalDurationMillis());
    }

    @Test
    void outOfOrderDuplicateAndNegativeDurationsDoNotInventFirstToken() {
        String jsonl = """
                {"protocol":"rd-agent-event/v1","eventType":"AGENT_SETTLED","sourceSequence":9,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT","occurredAt":"2026-08-14T01:00:10Z","payload":{}}
                {"protocol":"rd-agent-event/v1","eventType":"ASSISTANT_TEXT_DELTA","sourceSequence":2,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT","occurredAt":"2026-08-14T00:59:50Z","payload":{"delta":"early"}}
                {"protocol":"rd-agent-event/v1","eventType":"ASSISTANT_TEXT_DELTA","sourceSequence":2,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT","occurredAt":"2026-08-14T00:59:50Z","payload":{"delta":"early"}}
                {"protocol":"rd-agent-event/v1","eventType":"AGENT_STARTED","sourceSequence":1,"stageRunId":"stage-1","taskId":"task-1","role":"CODING_AGENT","occurredAt":"2026-08-14T01:00:00Z","payload":{}}
                {"protocol":"not-a-protocol","eventType":"ASSISTANT_TEXT_DELTA","sourceSequence":3,"occurredAt":"2026-08-14T01:00:01Z","payload":{"delta":"ignored"}}
                """;

        AgentRuntimeMeasurementSummary summary = parser.parse(jsonl, "pi", "anthropic", "claude-alias");

        assertTrue(summary.available());
        assertFalse(summary.firstTokenAvailable());
        assertEquals("NEGATIVE_DURATION", summary.parseErrorCategory());
        assertEquals(10_000L, summary.totalDurationMillis());
        assertEquals(2L, summary.droppedObservations());
    }

    @Test
    void providerRetryCountAndToolDurationAreMeasuredIndependently() {
        String jsonl = """
                {"protocol":"rd-agent-event/v1","eventType":"AGENT_STARTED","sourceSequence":1,"stageRunId":"s","taskId":"t","role":"CODING_AGENT","occurredAt":"2026-08-14T01:00:00Z","payload":{}}
                {"protocol":"rd-agent-event/v1","eventType":"PROVIDER_RETRYING","sourceSequence":2,"stageRunId":"s","taskId":"t","role":"CODING_AGENT","occurredAt":"2026-08-14T01:00:01Z","payload":{"attempt":1}}
                {"protocol":"rd-agent-event/v1","eventType":"PROVIDER_RETRYING","sourceSequence":3,"stageRunId":"s","taskId":"t","role":"CODING_AGENT","occurredAt":"2026-08-14T01:00:02Z","payload":{"attempt":2}}
                {"protocol":"rd-agent-event/v1","eventType":"TOOL_STARTED","sourceSequence":4,"stageRunId":"s","taskId":"t","role":"CODING_AGENT","occurredAt":"2026-08-14T01:00:03Z","payload":{"toolCallId":"tool-1"}}
                {"protocol":"rd-agent-event/v1","eventType":"TOOL_COMPLETED","sourceSequence":5,"stageRunId":"s","taskId":"t","role":"CODING_AGENT","occurredAt":"2026-08-14T01:00:05Z","payload":{"toolCallId":"tool-1"}}
                {"protocol":"rd-agent-event/v1","eventType":"ASSISTANT_TEXT_DELTA","sourceSequence":6,"stageRunId":"s","taskId":"t","role":"CODING_AGENT","occurredAt":"2026-08-14T01:00:06Z","payload":{"text":"done"}}
                {"protocol":"rd-agent-event/v1","eventType":"AGENT_SETTLED","sourceSequence":7,"stageRunId":"s","taskId":"t","role":"CODING_AGENT","occurredAt":"2026-08-14T01:00:07Z","payload":{}}
                """;

        AgentRuntimeMeasurementSummary summary = parser.parse(jsonl, "pi", "deepseek", "deepseek-alias");

        assertEquals(2, summary.providerRetryCount());
        assertEquals(2000L, summary.toolDurationMillis());
        assertTrue(summary.firstTokenAvailable());
        assertEquals(6000L, summary.firstTokenDurationMillis());
    }

    @Test
    void sameAttemptIdIsCountedOnceWhileDistinctFallbackAttemptsAreSummed() {
        String attempts = """
                [
                  {"attemptId":"att-1","runtime":"pi","provider":"deepseek","inputTokens":100,"outputTokens":10,"cacheReadInputTokens":0,"cacheCreationInputTokens":0,"estimatedCostUsd":"0.20","tokenUsageAvailable":true},
                  {"attemptId":"att-1","runtime":"pi","provider":"deepseek","inputTokens":100,"outputTokens":10,"cacheReadInputTokens":0,"cacheCreationInputTokens":0,"estimatedCostUsd":"0.20","tokenUsageAvailable":true},
                  {"attemptId":"att-2","runtime":"pi","provider":"anthropic","inputTokens":400,"outputTokens":80,"cacheReadInputTokens":20,"cacheCreationInputTokens":0,"estimatedCostUsd":"0.90","tokenUsageAvailable":true}
                ]
                """;

        AgentRuntimeMeasurementSummary summary = parser.aggregateProviderAttempts(attempts, "pi");

        assertTrue(summary.usageAvailable());
        assertEquals(2, summary.usageEventCount());
        assertEquals(500L, summary.inputTokens());
        assertEquals(90L, summary.outputTokens());
        assertEquals(20L, summary.cacheReadTokens());
        assertEquals(610L, summary.totalTokens());
    }

    @Test
    void claudeCompatEventsUseTheSameProtocolAndDoNotTreatProviderResponseAsTtft() {
        String jsonl = """
                {"protocol":"rd-agent-event/v1","eventType":"AGENT_STARTED","sourceSequence":1,"stageRunId":"claude-1","taskId":"task-c","role":"CODING_AGENT","occurredAt":"2026-08-14T02:00:00Z","payload":{}}
                {"protocol":"rd-agent-event/v1","eventType":"PROVIDER_RESPONDED","sourceSequence":2,"stageRunId":"claude-1","taskId":"task-c","role":"CODING_AGENT","occurredAt":"2026-08-14T02:00:04Z","payload":{}}
                {"protocol":"rd-agent-event/v1","eventType":"RUNTIME_STOPPED","sourceSequence":3,"stageRunId":"claude-1","taskId":"task-c","role":"CODING_AGENT","occurredAt":"2026-08-14T02:00:09Z","payload":{}}
                """;

        AgentRuntimeMeasurementSummary summary = parser.parse(jsonl, "claude-compat", "anthropic", "claude-alias");

        assertEquals("claude-compat", summary.runtime());
        assertFalse(summary.firstTokenAvailable());
        assertTrue(summary.firstProviderResponseAvailable());
        assertEquals(4000L, summary.firstProviderResponseDurationMillis());
        assertEquals(9000L, summary.totalDurationMillis());
        assertTrue(summary.finalized());
    }

    @Test
    void malformedInputIsUnavailableAndNeverThrows() {
        AgentRuntimeMeasurementSummary empty = parser.parse("   ");
        AgentRuntimeMeasurementSummary garbage = parser.parse("{not json}\n");
        assertFalse(empty.available());
        assertFalse(garbage.available());
        assertEquals("PARSE_ERROR", garbage.parseErrorCategory());
    }
}
