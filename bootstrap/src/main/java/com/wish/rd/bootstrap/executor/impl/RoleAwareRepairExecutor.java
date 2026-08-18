package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;

import java.util.Objects;

/**
 * Keeps every delivery role on the container Agent executor.
 * Reviewer and architect must not take the model-only HTTP shortcut.
 */
public final class RoleAwareRepairExecutor implements RepairExecutorPort {

    private final RepairExecutorPort codingExecutor;
    private final RepairExecutorPort modelOnlyExecutor;

    public RoleAwareRepairExecutor(
            RepairExecutorPort codingExecutor,
            RepairExecutorPort modelOnlyExecutor
    ) {
        this.codingExecutor = Objects.requireNonNull(codingExecutor, "codingExecutor must not be null");
        this.modelOnlyExecutor = Objects.requireNonNull(modelOnlyExecutor, "modelOnlyExecutor must not be null");
    }

    @Override
    public RepairExecutionResult execute(RepairJobCommand command) {
        return codingExecutor.execute(command);
    }
}
