package com.wish.rd.engine.requirement.job.model;

import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageCommandStore;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequirementStageCommandRemediationTest {

    @Test
    void shouldKeepNormalCheckpointAndRemediationGenerationIdentitiesMutuallyExclusive() {
        RequirementStageCommand remediation = remediation("101", "201", "CODING_AGENT");
        RequirementStageCommand claimed = remediation.claimed("worker", 1000L, 2L);

        assertEquals("201", claimed.remediationRoundId());
        assertEquals(AgentRemediationKind.QA_PRODUCT_FIX, claimed.remediationKind());
        assertEquals(1, claimed.remediationNo());
        assertThrows(IllegalArgumentException.class, () -> new RequirementStageCommand(
                remediation.commandId(), remediation.taskId(), remediation.taskVersion(), remediation.fencingToken(),
                remediation.role(), remediation.stage(), remediation.attemptNo(), remediation.maxAttempts(),
                remediation.deadlineEpochMillis(), remediation.resourceClass(), remediation.resourceRequirements(),
                remediation.projectId(), remediation.providerId(), remediation.priorityRank(), remediation.status(),
                remediation.leaseOwner(), remediation.leaseUntilEpochMillis(), remediation.nextVisibleAtEpochMillis(),
                remediation.lastError(), remediation.createdAtEpochMillis(), remediation.updatedAtEpochMillis(), "",
                "300", 300L, "binding", "201", AgentRemediationKind.QA_PRODUCT_FIX, 1));
    }

    @Test
    void shouldReplaySameRoundAndAllowTheNextRoundAsADistinctGeneration() {
        InMemoryRequirementStageCommandStore store = new InMemoryRequirementStageCommandStore();
        RequirementStageCommand first = store.enqueue(remediation("101", "201", "CODING_AGENT"));
        RequirementStageCommand replay = store.enqueue(remediation("101", "201", "CODING_AGENT"));
        RequirementStageCommand second = store.enqueue(RequirementStageCommand.remediationPending(
                "102", "1", 7L, 3L, "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 3, 1000L,
                ScheduleResourceClass.PROVIDER, Set.of(ScheduleResourceClass.PROVIDER), "project", "provider",
                "P1", "", "202", AgentRemediationKind.QA_PRODUCT_FIX, 2,
                "source-2", "{\"reason\":\"second\"}", digest("{\"reason\":\"second\"}"), 1L));

        assertEquals(first.commandId(), replay.commandId());
        assertEquals("202", second.remediationRoundId());
        assertEquals(2, store.findByRemediation("1", "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", "202")
                .orElseThrow().remediationNo());
    }

    @Test
    void shouldCarryTheImmutableHashBoundRequestAcrossLeaseTransitions() {
        String request = "{\"reason\":\"verified defect\"}";
        String hash = com.wish.rd.engine.requirement.policy.CanonicalJsonSha256.digest(request);
        RequirementStageCommand remediation = RequirementStageCommand.remediationPending(
                "101", "1", 7L, 3L, "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 3, 1000L,
                ScheduleResourceClass.PROVIDER, Set.of(ScheduleResourceClass.PROVIDER), "project", "provider",
                "P1", "", "201", AgentRemediationKind.QA_PRODUCT_FIX, 1,
                "source-stage-88", request, hash, 1L);

        RequirementStageCommand completed = remediation.claimed("worker", 1000L, 2L).succeeded(3L);

        assertEquals("source-stage-88", completed.remediationSourceStageRunId());
        assertEquals(request, completed.remediationRequestJson());
        assertEquals(hash, completed.remediationRequestHash());
        assertThrows(IllegalArgumentException.class, () -> RequirementStageCommand.remediationPending(
                "102", "1", 7L, 3L, "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 3, 1000L,
                ScheduleResourceClass.PROVIDER, Set.of(ScheduleResourceClass.PROVIDER), "project", "provider",
                "P1", "", "202", AgentRemediationKind.QA_PRODUCT_FIX, 2,
                "source-stage-89", request, "sha256:" + "0".repeat(64), 1L));
    }

    @Test
    void shouldAcceptHostVerifyFixRoundsIndependentlyAndRejectAThird() {
        RequirementStageCommand first = RequirementStageCommand.remediationPending(
                "201", "1", 7L, 3L, "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 3, 1000L,
                ScheduleResourceClass.PROVIDER, Set.of(ScheduleResourceClass.PROVIDER), "project", "provider",
                "P1", "", "301", AgentRemediationKind.HOST_VERIFY_FIX, 1,
                "coding-source", "{\"reason\":\"hv\"}", digest("{\"reason\":\"hv\"}"), 1L);
        RequirementStageCommand second = RequirementStageCommand.remediationPending(
                "202", "1", 7L, 3L, "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 3, 1000L,
                ScheduleResourceClass.PROVIDER, Set.of(ScheduleResourceClass.PROVIDER), "project", "provider",
                "P1", "", "302", AgentRemediationKind.HOST_VERIFY_FIX, 2,
                "coding-source-2", "{\"reason\":\"hv2\"}", digest("{\"reason\":\"hv2\"}"), 1L);
        assertEquals(AgentRemediationKind.HOST_VERIFY_FIX, first.remediationKind());
        assertEquals(2, second.remediationNo());
        assertThrows(IllegalArgumentException.class, () -> RequirementStageCommand.remediationPending(
                "203", "1", 7L, 3L, "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 3, 1000L,
                ScheduleResourceClass.PROVIDER, Set.of(ScheduleResourceClass.PROVIDER), "project", "provider",
                "P1", "", "303", AgentRemediationKind.HOST_VERIFY_FIX, 3,
                "coding-source-3", "{\"reason\":\"hv3\"}", digest("{\"reason\":\"hv3\"}"), 1L));
    }

    private static RequirementStageCommand remediation(String commandId, String roundId, String role) {
        return RequirementStageCommand.remediationPending(
                commandId, "1", 7L, 3L, role, "ROLE_EXECUTION:" + role, 3, 1000L,
                ScheduleResourceClass.PROVIDER, Set.of(ScheduleResourceClass.PROVIDER), "project", "provider",
                "P1", "", roundId, AgentRemediationKind.QA_PRODUCT_FIX, 1,
                "source-1", "{\"reason\":\"first\"}", digest("{\"reason\":\"first\"}"), 1L);
    }

    private static String digest(String json) {
        return com.wish.rd.engine.requirement.policy.CanonicalJsonSha256.digest(json);
    }
}
