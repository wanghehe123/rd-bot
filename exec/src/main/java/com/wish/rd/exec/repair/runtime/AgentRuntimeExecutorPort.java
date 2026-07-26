package com.wish.rd.exec.repair.runtime;

import com.wish.rd.exec.repair.runtime.model.AgentRuntimeExecutionRequest;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;

/** Requirement-delivery executor for one explicitly selected agent runtime. */
@FunctionalInterface
public interface AgentRuntimeExecutorPort {

    RepairExecutionResult execute(AgentRuntimeExecutionRequest request);
}
