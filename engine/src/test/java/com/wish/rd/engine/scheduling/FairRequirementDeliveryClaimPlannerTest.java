package com.wish.rd.engine.scheduling;

import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJob;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.model.FairScheduleLimits;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.engine.scheduling.model.StageScheduleCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FairRequirementDeliveryClaimPlannerTest {

    private final FairRequirementDeliveryClaimPlanner planner = new FairRequirementDeliveryClaimPlanner();

    @Test
    void shouldCapClaimsPerProjectAcrossPendingJobs() {
        long now = 10_000L;
        List<RequirementDeliveryJob> claimable = List.of(
                RequirementDeliveryJob.pending("j1", "t-a1", 3, 1_000L),
                RequirementDeliveryJob.pending("j2", "t-a2", 3, 1_100L),
                RequirementDeliveryJob.pending("j3", "t-a3", 3, 1_200L),
                RequirementDeliveryJob.pending("j4", "t-b1", 3, 1_300L)
        );
        Map<String, String> projects = Map.of(
                "t-a1", "proj-a",
                "t-a2", "proj-a",
                "t-a3", "proj-a",
                "t-b1", "proj-b"
        );
        FairScheduleLimits limits = new FairScheduleLimits(2, 8, 4, 8, 8, 60_000L);

        List<RequirementDeliveryJob> planned = planner.plan(
                claimable, List.of(), projects::get, limits, now
        );

        long projA = planned.stream().filter(j -> projects.get(j.taskId()).equals("proj-a")).count();
        long projB = planned.stream().filter(j -> projects.get(j.taskId()).equals("proj-b")).count();
        assertEquals(2, projA);
        assertEquals(1, projB);
        assertEquals(3, planned.size());
    }

    @Test
    void shouldRespectExistingInFlightTowardProjectCap() {
        long now = 10_000L;
        RequirementDeliveryJob running = RequirementDeliveryJob.pending("jr", "t-a0", 3, 500L)
                .claimed("worker-1", now + 60_000L, now);
        List<RequirementDeliveryJob> claimable = List.of(
                RequirementDeliveryJob.pending("j1", "t-a1", 3, 1_000L),
                RequirementDeliveryJob.pending("j2", "t-b1", 3, 1_100L)
        );
        Map<String, String> projects = Map.of(
                "t-a0", "proj-a",
                "t-a1", "proj-a",
                "t-b1", "proj-b"
        );
        FairScheduleLimits limits = new FairScheduleLimits(1, 8, 4, 8, 8, 60_000L);

        List<RequirementDeliveryJob> planned = planner.plan(
                claimable, List.of(running), projects::get, limits, now
        );

        assertEquals(1, planned.size());
        assertEquals("t-b1", planned.getFirst().taskId());
        assertTrue(planned.stream().noneMatch(j -> j.taskId().equals("t-a1")));
    }

    @Test
    void shouldRequireEveryResourceAndProviderCapacityBeforePlanningAClaim() {
        long now = 10_000L;
        FairScheduleLimits limits = new FairScheduleLimits(4, 1, 1, 2, 1, 8, 60_000L);
        RequirementStageCommand running = command(
                "running-coding", "project-a", "provider-a",
                Set.of(ScheduleResourceClass.PROVIDER, ScheduleResourceClass.DOCKER), now - 1_000L
        ).claimed("worker-a", now + 60_000L, now);
        RequirementStageCommand sameProvider = command(
                "same-provider", "project-b", "provider-a",
                Set.of(ScheduleResourceClass.PROVIDER), now - 900L
        );
        RequirementStageCommand dockerSaturated = command(
                "docker-saturated", "project-c", "provider-b",
                Set.of(ScheduleResourceClass.PROVIDER, ScheduleResourceClass.DOCKER), now - 800L
        );
        RequirementStageCommand available = command(
                "available", "project-d", "provider-b",
                Set.of(ScheduleResourceClass.PROVIDER), now - 700L
        );

        List<RequirementStageCommand> planned = planner.planStageCommands(
                List.of(sameProvider, dockerSaturated, available),
                List.of(running),
                limits,
                now,
                Map.of()
        );

        assertEquals(List.of("available"), planned.stream()
                .map(RequirementStageCommand::commandId)
                .toList());
    }

    @Test
    void shouldClassifyAiReviewAsProviderWork() {
        assertEquals(
                Set.of(ScheduleResourceClass.PROVIDER),
                FairRequirementDeliveryClaimPlanner.classifyStageRequirements(
                        "REQUIREMENT_DELIVERY", "AI_REVIEW")
        );
    }

    @Test
    void shouldClassifyHostVerifyAsDockerWork() {
        assertEquals(
                Set.of(ScheduleResourceClass.PROVIDER, ScheduleResourceClass.DOCKER),
                FairRequirementDeliveryClaimPlanner.classifyStageRequirements(
                        "REQUIREMENT_DELIVERY", "HOST_VERIFY")
        );
    }

    private static RequirementStageCommand command(
            String commandId,
            String projectId,
            String providerId,
            Set<ScheduleResourceClass> resourceRequirements,
            long createdAtEpochMillis
    ) {
        return RequirementStageCommand.pending(
                commandId,
                "task-" + commandId,
                0L,
                1L,
                "CODING_AGENT",
                "ROLE_EXECUTION:CODING_AGENT",
                0,
                3,
                createdAtEpochMillis + 60_000L,
                ScheduleResourceClass.PROVIDER,
                resourceRequirements,
                projectId,
                providerId,
                "P1",
                createdAtEpochMillis
        );
    }
}
