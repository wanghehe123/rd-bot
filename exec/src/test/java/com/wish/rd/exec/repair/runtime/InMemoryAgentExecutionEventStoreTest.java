package com.wish.rd.exec.repair.runtime;

import com.wish.rd.exec.repair.runtime.impl.InMemoryAgentExecutionEventStore;
import com.wish.rd.exec.repair.runtime.model.AgentExecutionEvent;
import com.wish.rd.exec.repair.runtime.model.AgentExecutionTraceSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryAgentExecutionEventStoreTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void assignsAuthoritativeSequenceDeduplicatesAndReplaysAfterCursor() throws Exception {
        InMemoryAgentExecutionEventStore store = new InMemoryAgentExecutionEventStore(8);
        List<AgentExecutionEvent> received = new ArrayList<>();
        store.subscribe("task-1", "stage-1", received::add);

        store.onEvent("container-1", event(1, "AGENT_STARTED"));
        store.onEvent("container-1", event(2, "TOOL_STARTED"));
        store.onEvent("container-1", event(2, "TOOL_STARTED"));
        store.onEvent("container-1", event(3, "AGENT_SETTLED"));

        AgentExecutionTraceSnapshot snapshot = store.snapshot("task-1", "stage-1", 1, 10);

        assertTrue(snapshot.available());
        assertTrue(snapshot.finalized());
        assertEquals(List.of(2L, 3L), snapshot.events().stream()
                .map(item -> item.path("sequence").asLong())
                .toList());
        assertEquals(List.of(1L, 2L, 3L), received.stream().map(AgentExecutionEvent::sequence).toList());
        assertEquals(3L, snapshot.nextSequence());
    }

    @Test
    void boundsTheRingAndReportsCursorTruncation() throws Exception {
        InMemoryAgentExecutionEventStore store = new InMemoryAgentExecutionEventStore(2);
        store.onEvent("container-1", event(1, "AGENT_STARTED"));
        store.onEvent("container-1", event(2, "TURN_STARTED"));
        store.onEvent("container-1", event(3, "AGENT_SETTLED"));

        AgentExecutionTraceSnapshot snapshot = store.snapshot("task-1", "stage-1", 0, 10);

        assertTrue(snapshot.truncated());
        assertEquals(List.of(2L, 3L), snapshot.events().stream()
                .map(item -> item.path("sequence").asLong())
                .toList());
    }

    @Test
    void subscriberFailureDoesNotBreakTheExecutionSink() throws Exception {
        InMemoryAgentExecutionEventStore store = new InMemoryAgentExecutionEventStore(8);
        AtomicInteger healthySubscriberCalls = new AtomicInteger();
        store.subscribe("task-1", "stage-1", ignored -> {
            throw new IllegalStateException("browser disconnected");
        });
        store.subscribe("task-1", "stage-1", ignored -> healthySubscriberCalls.incrementAndGet());

        store.onEvent("container-1", event(1, "RUNTIME_READY"));

        assertEquals(1, healthySubscriberCalls.get());
        assertFalse(store.snapshot("task-1", "stage-2", 0, 10).available());
    }

    private static JsonNode event(long sourceSequence, String eventType) throws Exception {
        return OBJECT_MAPPER.readTree("""
                {"protocol":"rd-agent-event/v1","sourceSequence":%d,"taskId":"task-1","stageRunId":"stage-1","role":"CODING_AGENT","eventType":"%s"}
                """.formatted(sourceSequence, eventType));
    }
}
