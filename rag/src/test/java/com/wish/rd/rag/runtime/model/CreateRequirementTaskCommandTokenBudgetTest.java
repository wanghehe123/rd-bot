package com.wish.rd.rag.runtime.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CreateRequirementTaskCommandTokenBudgetTest {

    @Test
    void shouldRetainNonNegativeTokenBudgetOverride() {
        CreateRequirementTaskCommand command = new CreateRequirementTaskCommand(
                "title", "P2", "ADMIN", "", "", "project-1", "project", "Project",
                "https://example/repo.git", "owner", "repo", "main", "expected", List.of("accept"), List.of(), false,
                90_000L
        );

        assertEquals(90_000L, command.tokenBudgetOverride());
    }

    @Test
    void shouldRejectNegativeTokenBudgetOverride() {
        assertThrows(IllegalArgumentException.class, () -> new CreateRequirementTaskCommand(
                "title", "P2", "ADMIN", "", "", "project-1", "project", "Project",
                "https://example/repo.git", "owner", "repo", "main", "expected", List.of("accept"), List.of(), false,
                -1L
        ));
    }
}
