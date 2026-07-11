package com.wish.rd.engine.agent;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentStageProgressCalculatorTest {

    @Test
    void shouldUseLatestAttemptAndIgnoreRolesOutsideBugFixWorkflow() {
        AgentStageProgressCalculator calculator = new AgentStageProgressCalculator();

        AgentStageProgressCalculator.AgentStageProgress result = calculator.calculate("BUG_FIX", List.of(
                stage("evidence-1", AgentRole.BUG_EVIDENCE_COLLECTOR, AgentStageStatus.SUCCEEDED, 1, "collector", 100L),
                stage("coding-1", AgentRole.BUG_CODING_AGENT, AgentStageStatus.FAILED_RETRYABLE, 1, "long-cat", 110L),
                stage("coding-2", AgentRole.BUG_CODING_AGENT, AgentStageStatus.RUNNING, 2, "minimax", 120L),
                stage("requirement-1", AgentRole.REQUIREMENT_REVIEWER, AgentStageStatus.RUNNING, 9, "ignored", 130L)
        ));

        assertEquals("BUG_CODING_AGENT", result.currentRole());
        assertEquals("RUNNING", result.currentStatus());
        assertEquals(9, result.completedSteps());
        assertEquals(24, result.totalSteps());
        assertEquals("minimax", result.provider());
        assertEquals(1, result.retryCount());
        assertEquals(2, result.latestByRole().size());
    }

    @Test
    void shouldReturnEmptyProgressWhenNoStageHasBeenCreated() {
        AgentStageProgressCalculator calculator = new AgentStageProgressCalculator();

        AgentStageProgressCalculator.AgentStageProgress result = calculator.calculate("REQUIREMENT", List.of());

        assertEquals("", result.currentRole());
        assertEquals("", result.currentStatus());
        assertEquals(0, result.completedSteps());
        assertEquals(24, result.totalSteps());
        assertEquals("", result.provider());
        assertEquals(0, result.retryCount());
    }

    private static AgentStageRun stage(
            String id,
            AgentRole role,
            AgentStageStatus status,
            int attemptNo,
            String provider,
            long createdAt
    ) {
        return new AgentStageRun(
                id,
                "task-1",
                role,
                status,
                attemptNo,
                "task-1:" + role + ":" + attemptNo,
                "",
                "",
                "",
                provider,
                "[]",
                "{}",
                "",
                "",
                createdAt,
                createdAt,
                0L,
                0L
        );
    }
}
