package com.wish.rd.exec.repair.runtime.model;

import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;

import java.util.Objects;

/** Immutable command plus the already-resolved profile snapshot for one attempt. */
public record AgentRuntimeExecutionRequest(
        AgentExecutionProfileSnapshot snapshot,
        RepairJobCommand command
) {

    public AgentRuntimeExecutionRequest {
        snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        command = Objects.requireNonNull(command, "command must not be null");
    }
}
