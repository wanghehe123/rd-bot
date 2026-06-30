package com.wish.rd.exec.repair.security;

import com.wish.rd.exec.repair.execution.RepairJobCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionAllowlistPolicyTest {

    @Test
    void shouldAllowWhenDisabled() {
        ExecutionAllowlistPolicy policy = ExecutionAllowlistPolicy.disabled();

        assertTrue(policy.evaluate(command("evil", "repo", "main", "hotfix/x")).allowed());
    }

    @Test
    void shouldMatchRepositoryAndBranchPatterns() {
        ExecutionAllowlistPolicy policy = new ExecutionAllowlistPolicy(
                true,
                List.of("https://github.com/example/*"),
                List.of("example/order"),
                List.of("main"),
                List.of("repair/*", "requirement/*")
        );

        assertTrue(policy.evaluate(command("example", "order", "main", "repair/FS-1001")).allowed());
        assertTrue(policy.evaluate(command("example", "order", "main", "requirement/task-1001")).allowed());
        assertFalse(policy.evaluate(command("example", "order", "dev", "repair/FS-1001")).allowed());
        assertFalse(policy.evaluate(command("other", "order", "main", "repair/FS-1001")).allowed());
        assertFalse(policy.evaluate(command("example", "order", "main", "feature/free-form")).allowed());
    }

    @Test
    void shouldRejectEnabledPolicyWithoutRules() {
        ExecutionAllowlistPolicy policy = new ExecutionAllowlistPolicy(true, List.of(), List.of(), List.of(), List.of());

        assertFalse(policy.evaluate(command("example", "order", "main", "repair/FS-1001")).allowed());
    }

    private static RepairJobCommand command(String owner, String name, String baseBranch, String workBranch) {
        return new RepairJobCommand(
                "repair-1001",
                "task-1001",
                "FS-1001",
                "Order service fails",
                "Fix the order service regression.",
                "https://github.com/" + owner + "/" + name + ".git",
                owner,
                name,
                baseBranch,
                workBranch,
                Map.of(),
                Map.of()
        );
    }
}
