package com.wish.rd.engine.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentStagePlannerTest {

    @Test
    void shouldPlanRequirementDeliveryStagesInStableRoleOrder() {
        AtomicInteger sequence = new AtomicInteger();
        AgentStagePlanner planner = new AgentStagePlanner(() -> "stage-" + sequence.incrementAndGet());

        List<AgentStageRun> stages = planner.planRequirementDelivery("task-1001", 1_783_000_000_000L);

        assertEquals(List.of(
                AgentRole.REQUIREMENT_REVIEWER,
                AgentRole.SOLUTION_ARCHITECT,
                AgentRole.CODING_AGENT,
                AgentRole.QA_AGENT
        ), stages.stream().map(AgentStageRun::role).toList());
        assertEquals(List.of("stage-1", "stage-2", "stage-3", "stage-4"),
                stages.stream().map(AgentStageRun::stageRunId).toList());
        assertEquals(List.of(
                "task-1001:REQUIREMENT_REVIEWER:1",
                "task-1001:SOLUTION_ARCHITECT:1",
                "task-1001:CODING_AGENT:1",
                "task-1001:QA_AGENT:1"
        ), stages.stream().map(AgentStageRun::idempotencyKey).toList());
        assertEquals(List.of(AgentStageStatus.PENDING, AgentStageStatus.PENDING,
                AgentStageStatus.PENDING, AgentStageStatus.PENDING),
                stages.stream().map(AgentStageRun::status).toList());
    }
}
