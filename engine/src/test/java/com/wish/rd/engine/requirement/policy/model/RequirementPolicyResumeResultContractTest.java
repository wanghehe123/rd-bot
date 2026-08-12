package com.wish.rd.engine.requirement.policy.model;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract tests for the atomic approval-resume consumer result. */
class RequirementPolicyResumeResultContractTest {
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void acceptsOnlyTheFirstCleanPendingDeliveryContinuation() {
        assertDoesNotThrow(() -> new RequirementPolicyResumeResult(applied(), completedResume(), continuation(), 7L, 9L));
        assertThrows(IllegalArgumentException.class, () -> new RequirementPolicyResumeResult(
                applied(), completedResume(), pending("SOLUTION_ARCHITECT", "ROLE_EXECUTION:SOLUTION_ARCHITECT", 7L, 9L), 7L, 9L));
        assertThrows(IllegalArgumentException.class, () -> new RequirementPolicyResumeResult(
                applied(), completedResume(), pending("REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER", 7L, 10L), 7L, 9L));
    }

    @Test
    void rejectsNonResumeCompletionAndLeasedOrUnderspecifiedContinuations() {
        RequirementStageCommand wrongCompleted = RequirementStageCommand.pending(
                "901", "201", 6L, 8L, "OTHER", "APPROVAL_RESUME", 0, 3, NOW + 1_000L,
                ScheduleResourceClass.GENERIC, "project-1", "provider", "P1", NOW).succeeded(NOW);
        assertThrows(IllegalArgumentException.class, () -> new RequirementPolicyResumeResult(
                applied(), wrongCompleted, continuation(), 7L, 9L));
        assertThrows(IllegalArgumentException.class, () -> new RequirementPolicyResumeResult(
                applied(), completedResume(), continuation().claimed("worker", NOW + 10L, NOW), 7L, 9L));
        RequirementStageCommand noProvider = RequirementStageCommand.pending(
                "902", "201", 7L, 9L, "REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER",
                0, 3, NOW + 1_000L, ScheduleResourceClass.PROVIDER, Set.of(ScheduleResourceClass.PROVIDER),
                "project-1", "", "P1", NOW);
        assertThrows(IllegalArgumentException.class, () -> new RequirementPolicyResumeResult(
                applied(), completedResume(), noProvider, 7L, 9L));
    }

    private static RequirementPolicyRun applied() {
        String plan = "{\"plan\":true}";
        String policy = "{\"action\":\"WAITING_APPROVAL\"}";
        return new RequirementPolicyRun("101", "201", 3L, 5L, plan, RequirementPolicyRun.canonicalJsonDigest(plan),
                policy, RequirementPolicyRun.canonicalJsonDigest(policy), "WAITING_APPROVAL", RequirementPolicyRunState.APPLIED,
                7L, 9L, 5L, 7L, "request", "host", "safe", NOW, "901", "901", NOW + 1L,
                5L, NOW, NOW + 1L);
    }

    private static RequirementStageCommand completedResume() {
        return RequirementStageCommand.pending("901", "201", 6L, 8L, "REQUIREMENT_DELIVERY", "APPROVAL_RESUME",
                0, 3, NOW + 1_000L, ScheduleResourceClass.GENERIC,
                Set.of(ScheduleResourceClass.GENERIC), "project-1", "provider", "P1", "101", NOW).claimed(
                        "worker", NOW + 10_000L, NOW).succeeded(NOW + 1L);
    }

    private static RequirementStageCommand continuation() {
        return pending("REQUIREMENT_REVIEWER", "ROLE_EXECUTION:REQUIREMENT_REVIEWER", 7L, 9L);
    }

    private static RequirementStageCommand pending(String role, String stage, long version, long fence) {
        return RequirementStageCommand.pending("902", "201", version, fence, role, stage, 0, 3, NOW + 1_000L,
                ScheduleResourceClass.PROVIDER, Set.of(ScheduleResourceClass.PROVIDER),
                "project-1", "provider", "P1", "101", NOW);
    }
}
