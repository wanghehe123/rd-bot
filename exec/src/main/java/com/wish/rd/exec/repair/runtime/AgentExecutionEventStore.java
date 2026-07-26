package com.wish.rd.exec.repair.runtime;

import com.wish.rd.exec.repair.runtime.model.AgentExecutionEvent;
import com.wish.rd.exec.repair.runtime.model.AgentExecutionTraceSnapshot;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Consumer;

/**
 * Normalized event sink plus a bounded read/subscription surface for one-way observation.
 * Implementations must not expose raw provider/session payloads.
 */
public interface AgentExecutionEventStore extends AgentExecutionEventSink {

    AgentExecutionTraceSnapshot snapshot(String taskId, String stageRunId, long afterSequence, int limit);

    AutoCloseable subscribe(String taskId, String stageRunId, Consumer<AgentExecutionEvent> listener);

    static AgentExecutionEventStore noop() {
        return new AgentExecutionEventStore() {
            @Override
            public void onEvent(String containerName, JsonNode event) {
            }

            @Override
            public AgentExecutionTraceSnapshot snapshot(
                    String taskId,
                    String stageRunId,
                    long afterSequence,
                    int limit
            ) {
                return AgentExecutionTraceSnapshot.unavailable("LIVE");
            }

            @Override
            public AutoCloseable subscribe(
                    String taskId,
                    String stageRunId,
                    Consumer<AgentExecutionEvent> listener
            ) {
                return () -> {
                };
            }
        };
    }
}
