package com.wish.rd.exec.repair.security;

import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.security.model.ExecutionAllowlistPolicy;
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
    void shouldTreatGithubRepositoryUrlGitSuffixAsOptional() {
        ExecutionAllowlistPolicy policy = new ExecutionAllowlistPolicy(
                true,
                List.of("https://github.com/example/order.git"),
                List.of("example/order"),
                List.of("main"),
                List.of("requirement/*")
        );

        assertTrue(policy.evaluate(command(
                "example",
                "order",
                "https://github.com/example/order",
                "main",
                "requirement/task-1001"
        )).allowed());
    }

    @Test
    void shouldAllowRegisteredProjectRepositoryWhenStaticAllowlistDoesNotMatch() {
        ExecutionAllowlistPolicy policy = new ExecutionAllowlistPolicy(
                true,
                List.of("https://github.com/example-owner/example-repo.git"),
                List.of("example-owner/example-repo"),
                List.of("main"),
                List.of("requirement/*"),
                (url, ownerAndName) -> ownerAndName.equalsIgnoreCase(
                        "wanghehe123/rd-bot-waimai-acceptance-20260624-141045"
                )
        );

        assertTrue(policy.evaluate(command(
                "wanghehe123",
                "rd-bot-waimai-acceptance-20260624-141045",
                "https://github.com/wanghehe123/rd-bot-waimai-acceptance-20260624-141045",
                "main",
                "requirement/7495092282882920448"
        )).allowed());
    }

    @Test
    void shouldRejectUnknownRepositoryAndIncludeTheUrlInTheReason() {
        ExecutionAllowlistPolicy policy = new ExecutionAllowlistPolicy(
                true,
                List.of("https://github.com/example-owner/example-repo.git"),
                List.of("example-owner/example-repo"),
                List.of("main"),
                List.of("requirement/*")
        );

        var decision = policy.evaluate(command(
                "wanghehe123",
                "rd-bot-waimai-acceptance-20260624-141045",
                "https://github.com/wanghehe123/rd-bot-waimai-acceptance-20260624-141045",
                "main",
                "requirement/7495092282882920448"
        ));

        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("repositoryUrl is not allowlisted"));
        assertTrue(decision.reason().contains("wanghehe123/rd-bot-waimai-acceptance-20260624-141045"));
    }

    @Test
    void shouldStillEnforceWorkBranchRulesForARegisteredProject() {
        ExecutionAllowlistPolicy policy = new ExecutionAllowlistPolicy(
                true,
                List.of("https://github.com/example-owner/example-repo.git"),
                List.of("example-owner/example-repo"),
                List.of("main"),
                List.of("requirement/*"),
                (url, ownerAndName) -> true
        );

        assertFalse(policy.evaluate(command(
                "wanghehe123",
                "rd-bot-waimai-acceptance-20260624-141045",
                "https://github.com/wanghehe123/rd-bot-waimai-acceptance-20260624-141045",
                "main",
                "feature/unbound"
        )).allowed());
    }

    @Test
    void shouldRejectEnabledPolicyWithoutRules() {
        ExecutionAllowlistPolicy policy = new ExecutionAllowlistPolicy(true, List.of(), List.of(), List.of(), List.of());

        assertFalse(policy.evaluate(command("example", "order", "main", "repair/FS-1001")).allowed());
    }

    @Test
    void shouldSplitCommaSeparatedPatternsInsideOneEntry() {
        ExecutionAllowlistPolicy policy = new ExecutionAllowlistPolicy(
                true,
                List.of("https://github.com/example/order.git, https://github.com/example/billing.git"),
                List.of("example/order,example/billing"),
                List.of("main, swebench/*"),
                List.of("repair/*,requirement/*")
        );

        assertTrue(policy.evaluate(command("example", "order", "main", "requirement/task-1001")).allowed());
        assertTrue(policy.evaluate(command("example", "billing", "swebench/django-10924", "repair/FS-1")).allowed());
        assertFalse(policy.evaluate(command("example", "payment", "main", "requirement/task-1001")).allowed());
    }

    private static RepairJobCommand command(String owner, String name, String baseBranch, String workBranch) {
        return command(owner, name, "https://github.com/" + owner + "/" + name + ".git", baseBranch, workBranch);
    }

    private static RepairJobCommand command(
            String owner,
            String name,
            String repositoryUrl,
            String baseBranch,
            String workBranch
    ) {
        return new RepairJobCommand(
                "repair-1001",
                "task-1001",
                "FS-1001",
                "Order service fails",
                "Fix the order service regression.",
                repositoryUrl,
                owner,
                name,
                baseBranch,
                workBranch,
                Map.of(),
                Map.of()
        );
    }
}
