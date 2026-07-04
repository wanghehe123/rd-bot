package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.execution.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.execution.RepairJobCommand;

import java.util.Locale;
import java.util.Objects;

/**
 * Routes planning-only Agent stages to a model-only executor while keeping executable stages in Docker.
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
        String role = command.contextJson().getOrDefault("agentRole", "")
                .strip()
                .toUpperCase(Locale.ROOT);
        if ("REQUIREMENT_REVIEWER".equals(role) || "SOLUTION_ARCHITECT".equals(role)) {
            return modelOnlyExecutor.execute(command);
        }
        return codingExecutor.execute(command);
    }
}
