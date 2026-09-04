package com.wish.rd.bootstrap.threading;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.model.FairScheduleLimits;
import com.wish.rd.engine.scheduling.model.RequirementDeliverySchedulingPolicy;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementStageCommandFactoryTest {

    @Test
    void createsCheckpointBoundRoleAiAndInfrastructureCommandsWithExactTargetSemantics() {
        RequirementStageCommandFactory factory = factory();

        RequirementStageCommand role = factory.createRetryPendingCommand(
                "task-1", 4L, 9L, "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT",
                "project-a", "P1", "provider-a", "policy-1", "101", 101L, "binding-role", 100L);
        RequirementStageCommand ai = factory.createRetryPendingCommand(
                "task-1", 4L, 9L, "REQUIREMENT_DELIVERY", "AI_REVIEW",
                "project-a", "P1", "provider-a", "policy-1", "101", 101L, "binding-ai", 100L);
        RequirementStageCommand infrastructure = factory.createRetryPendingCommand(
                "task-1", 4L, 9L, "REQUIREMENT_DELIVERY", "PLAN_GENERATED",
                "project-a", "P1", "provider-a", "", "101", 101L, "", 100L);

        assertTarget(role, "binding-role");
        assertTarget(ai, "binding-ai");
        assertAll(
                () -> assertEquals("101", infrastructure.retryCheckpointId()),
                () -> assertEquals(101L, infrastructure.businessGeneration()),
                () -> assertEquals("", infrastructure.targetRetryBindingId()),
                () -> assertTrue(infrastructure.commandId().matches("\\d+"))
        );
    }

    @Test
    void createsHostVerifyCommandWithDockerResourceAndDefaultDeadline() {
        RequirementStageCommand command = factory().createPendingCommand(
                "task-1", 4L, 9L, "REQUIREMENT_DELIVERY", "HOST_VERIFY",
                "project-a", "P1", "provider-a", "policy-1", 100L);
        assertEquals("REQUIREMENT_DELIVERY", command.role());
        assertEquals("HOST_VERIFY", command.stage());
        assertEquals(Set.of(
                com.wish.rd.engine.scheduling.model.ScheduleResourceClass.PROVIDER,
                com.wish.rd.engine.scheduling.model.ScheduleResourceClass.DOCKER),
                command.resourceRequirements());
        assertEquals(com.wish.rd.engine.scheduling.model.ScheduleResourceClass.DOCKER, command.resourceClass());
        assertEquals(100L + 60_000L, command.deadlineEpochMillis());
    }

    private static void assertTarget(RequirementStageCommand command, String targetBindingId) {
        assertAll(
                () -> assertEquals("101", command.retryCheckpointId()),
                () -> assertEquals(101L, command.businessGeneration()),
                () -> assertEquals(targetBindingId, command.targetRetryBindingId()),
                () -> assertTrue(command.commandId().matches("\\d+"))
        );
    }

    private static RequirementStageCommandFactory factory() {
        AtomicLong clock = new AtomicLong(1L);
        return new RequirementStageCommandFactory(
                new SnowflakeIdGenerator(1, 1, clock::getAndIncrement),
                3,
                new RequirementDeliverySchedulingPolicy(
                        FairScheduleLimits.defaults(), java.util.Map.of(), 60_000L, "provider-default"),
                null);
    }
}
