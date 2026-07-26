package com.wish.rd.exec.repair.runtime;

import com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit strategy boundary for Requirement Delivery.
 *
 * <p>The router receives an immutable snapshot and never reads project
 * configuration, provider registries, or task overrides while executing.</p>
 */
public final class AgentRuntimeRouter {

    private final Map<AgentRuntimeType, AgentRuntimeExecutorPort> executors;

    public AgentRuntimeRouter(Map<AgentRuntimeType, AgentRuntimeExecutorPort> executors) {
        if (executors == null) {
            throw new IllegalArgumentException("executors must not be null");
        }
        EnumMap<AgentRuntimeType, AgentRuntimeExecutorPort> copy = new EnumMap<>(AgentRuntimeType.class);
        executors.forEach((runtimeType, executor) -> {
            if (runtimeType == null || executor == null) {
                throw new IllegalArgumentException("runtime executor entries must not be null");
            }
            copy.put(runtimeType, executor);
        });
        this.executors = Map.copyOf(copy);
    }

    public RepairExecutionResult execute(AgentRuntimeExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        AgentExecutionProfileSnapshot snapshot = request.snapshot();
        if (!snapshot.hasValidIntegrityHash()) {
            throw new IllegalArgumentException(
                    "execution profile snapshot integrity hash does not match: " + snapshot.snapshotId()
            );
        }
        AgentRuntimeExecutorPort executor = executors.get(snapshot.runtimeType());
        if (executor == null) {
            throw new UnsupportedAgentRuntimeException(
                    "no executor registered for agent runtime: " + snapshot.runtimeType()
            );
        }
        RepairExecutionResult result = executor.execute(request);
        if (result == null) {
            throw new IllegalStateException(
                    "agent runtime executor returned null: " + snapshot.runtimeType()
            );
        }
        return result;
    }
}
