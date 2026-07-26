package com.wish.rd.exec.repair.runtime;

import com.fasterxml.jackson.databind.JsonNode;

/** Receives already protocol-validated normalized runtime events during execution. */
@FunctionalInterface
public interface AgentExecutionEventSink {

    void onEvent(String containerName, JsonNode event);

    static AgentExecutionEventSink noop() {
        return (containerName, event) -> {
        };
    }
}
