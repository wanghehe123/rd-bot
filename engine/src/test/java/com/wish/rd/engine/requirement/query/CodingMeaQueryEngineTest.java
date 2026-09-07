package com.wish.rd.engine.requirement.query;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.requirement.audit.AuditCompletion;
import com.wish.rd.engine.requirement.audit.AuditIntegrity;
import com.wish.rd.engine.requirement.audit.AuditRun;
import com.wish.rd.engine.requirement.audit.AuditedContractRef;
import com.wish.rd.engine.requirement.audit.AuditedRecord;
import com.wish.rd.engine.requirement.audit.AuditedRecordKind;
import com.wish.rd.engine.requirement.audit.AuditedRecordStatus;
import com.wish.rd.engine.requirement.audit.AuditedTaskState;
import com.wish.rd.engine.requirement.audit.ContractAuditVerdict;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.manager.ManagerDecideStages;
import com.wish.rd.engine.requirement.manager.ManagerDecision;
import com.wish.rd.engine.requirement.manager.ManagerRoute;
import com.wish.rd.engine.requirement.verify.model.HostVerificationRun;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingMeaQueryEngineTest {

    private final CodingMeaQueryEngine engine = new CodingMeaQueryEngine();

    @Test
    void missingCodingStageIsAvailableFalseWithoutCreatingNeighborhood() {
        CodingMeaSnapshot snapshot = snapshot(
                "task-empty",
                "",
                true,
                state("task-empty", 1L, "sha256:" + "a".repeat(64), List.of()),
                Map.of(),
                List.of(),
                List.of(),
                Set.of(),
                false,
                "",
                List.of(),
                List.of()
        );

        CodingMeaResponse response = engine.query(snapshot);

        assertFalse(response.available());
        assertEquals(CodingMeaQueryEngine.NO_CODING_STAGE, response.unavailableReason());
        assertNull(response.codingStageRunId());
        assertTrue(response.commands().isEmpty());
        assertTrue(response.decisions().isEmpty());
        assertTrue(response.links().isEmpty());
    }

    @Test
    void firstCodingWithoutManagerIsNotASystemError() {
        AgentStageRun coding = stage("stage-coding-1", "task-first", AgentRole.CODING_AGENT, 1);
        RequirementStageCommand command = command(
                "cmd-coding-1", "task-first", "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 1_000L);
        AuditRun audit = audit("audit-1", "task-first", "stage-coding-1", "CODING_AGENT", "cmd-coding-1");
        CodingMeaSnapshot snapshot = snapshot(
                "task-first",
                "stage-coding-1",
                false,
                state("task-first", 1L, "sha256:" + "b".repeat(64), List.of(pendingAc("AC-001"))),
                Map.of(),
                List.of(coding),
                List.of(command),
                Set.of("cmd-coding-1"),
                false,
                "",
                List.of(),
                List.of(audit)
        );

        CodingMeaResponse response = engine.query(snapshot);

        assertTrue(response.available());
        assertNull(response.unavailableReason());
        assertEquals("stage-coding-1", response.codingStageRunId());
        assertTrue(response.decisions().isEmpty());
        assertEquals("stage-coding-1", response.commands().getFirst().stageRunId());
        assertNull(response.commands().getFirst().stageLinkReason());
    }

    @Test
    void stateAtDecisionUsesRevisionEightNotHeadTen() {
        AuditedTaskState head = state("task-rev", 10L, "sha256:" + "1".repeat(64), List.of(pendingAc("AC-HEAD")));
        AuditedTaskState revision8 = state("task-rev", 8L, "sha256:" + "8".repeat(64), List.of(pendingAc("AC-003")));
        ManagerDecision decision = ManagerDecision.of(
                "task-rev", 1, "cmd-hv-1", 8L, revision8.stateHash(),
                ManagerRoute.EXECUTE, List.of(), "", "QA_AGENT", "host-verify succeeded; continue QA");
        RequirementStageCommand hv = command(
                "cmd-hv-1", "task-rev", "REQUIREMENT_DELIVERY", "HOST_VERIFY", 2_000L);
        RequirementStageCommand manager = command(
                "cmd-manager-1", "task-rev", "REQUIREMENT_DELIVERY",
                ManagerDecideStages.forSource("cmd-hv-1"), 2_100L);
        HostVerificationRun hvRun = new HostVerificationRun(
                "hv-1", "task-rev", "stage-coding-1", "", 1,
                HostVerificationStatus.SUCCEEDED, false, "", "", 0, 2_000L, 2_000L, 2_010L);
        CodingMeaSnapshot snapshot = snapshot(
                "task-rev",
                "stage-coding-1",
                false,
                head,
                Map.of(8L, revision8, 10L, head),
                List.of(stage("stage-coding-1", "task-rev", AgentRole.CODING_AGENT, 1)),
                List.of(hv, manager),
                Set.of("cmd-hv-1", "cmd-manager-1"),
                false,
                "",
                List.of(decision),
                List.of(),
                List.of(hvRun),
                List.of()
        );

        CodingMeaResponse response = engine.query(snapshot);

        CodingMeaResponse.DecisionReference projected = response.decisions().getFirst();
        assertTrue(projected.stateAtDecision().available());
        assertEquals(8L, projected.stateAtDecision().stateVersion());
        assertEquals("AC-003", projected.stateAtDecision().records().getFirst().id());
        assertFalse(projected.stateAtDecision().records().stream().anyMatch(record -> "AC-HEAD".equals(record.id())));
        assertEquals(2_100L, projected.commandCreatedAtEpochMillis());
        assertEquals("cmd-manager-1", projected.managerCommandId());
        assertTrue(response.links().stream().anyMatch(link ->
                "VERIFIES".equals(link.relation()) && "stage-coding-1".equals(link.toId()) && link.available()));
    }

    @Test
    void missingRevisionIsUnavailableAndDoesNotUseHead() {
        AuditedTaskState head = state("task-rev", 10L, "sha256:" + "1".repeat(64), List.of(pendingAc("AC-HEAD")));
        ManagerDecision decision = ManagerDecision.of(
                "task-rev", 1, "cmd-hv-1", 8L, "sha256:" + "8".repeat(64),
                ManagerRoute.ASK, List.of(), "", "", "need operator");
        CodingMeaSnapshot snapshot = snapshot(
                "task-rev",
                "stage-coding-1",
                false,
                head,
                Map.of(),
                List.of(stage("stage-coding-1", "task-rev", AgentRole.CODING_AGENT, 1)),
                List.of(),
                Set.of(),
                false,
                "",
                List.of(decision),
                List.of()
        );

        CodingMeaResponse.StateSlice slice = engine.query(snapshot).decisions().getFirst().stateAtDecision();

        assertFalse(slice.available());
        assertEquals(CodingMeaQueryEngine.REVISION_UNAVAILABLE, slice.unavailableReason());
        assertTrue(slice.records().isEmpty());
    }

    @Test
    void twoQaAttemptsStayDistinctAndCommandAttemptIsNotStageAttempt() {
        AgentStageRun qa1 = stage("stage-qa-1", "task-qa", AgentRole.QA_AGENT, 1);
        AgentStageRun qa2 = stage("stage-qa-2", "task-qa", AgentRole.QA_AGENT, 2);
        RequirementStageCommand qaCommand = command(
                "cmd-qa-1", "task-qa", "QA_AGENT", "ROLE_EXECUTION:QA_AGENT", 3_000L);
        AuditRun audit = audit("audit-qa-2", "task-qa", "stage-qa-2", "QA_AGENT", "cmd-qa-1");
        CodingMeaSnapshot snapshot = snapshot(
                "task-qa",
                "stage-coding-1",
                false,
                state("task-qa", 1L, "sha256:" + "c".repeat(64), List.of()),
                Map.of(),
                List.of(stage("stage-coding-1", "task-qa", AgentRole.CODING_AGENT, 1), qa1, qa2),
                List.of(qaCommand),
                Set.of("cmd-qa-1"),
                false,
                "",
                List.of(),
                List.of(audit)
        );

        CodingMeaResponse response = engine.query(snapshot);

        assertEquals(List.of("stage-qa-1", "stage-qa-2"),
                response.qaStages().stream().map(CodingMeaResponse.StageReference::stageRunId).toList());
        assertEquals(2, response.qaStages().get(1).attemptNo());
        assertEquals(0, response.commands().getFirst().commandAttemptNo());
        assertEquals("stage-qa-2", response.commands().getFirst().stageRunId());
    }

    @Test
    void outsidePageKeepsTargetId() {
        ManagerDecision decision = ManagerDecision.of(
                "task-page", 1, "cmd-hv-1", 1L, "sha256:" + "d".repeat(64),
                ManagerRoute.ASK, List.of(), "", "", "ask");
        RequirementStageCommand manager = command(
                "cmd-manager-1", "task-page", "REQUIREMENT_DELIVERY",
                ManagerDecideStages.forSource("cmd-hv-1"), 4_000L);
        CodingMeaSnapshot snapshot = snapshot(
                "task-page",
                "stage-coding-1",
                false,
                state("task-page", 1L, "sha256:" + "d".repeat(64), List.of()),
                Map.of(1L, state("task-page", 1L, "sha256:" + "d".repeat(64), List.of())),
                List.of(stage("stage-coding-1", "task-page", AgentRole.CODING_AGENT, 1)),
                List.of(manager),
                Set.of("cmd-hv-1", "cmd-manager-1"),
                true,
                "next",
                List.of(decision),
                List.of()
        );

        CodingMeaResponse.MeaLink link = engine.query(snapshot).links().stream()
                .filter(item -> "DECIDED_AFTER".equals(item.relation()))
                .findFirst()
                .orElseThrow();

        assertEquals("cmd-hv-1", link.toId());
        assertFalse(link.available());
        assertEquals(CodingMeaQueryEngine.OUTSIDE_PAGE, link.unavailableReason());
        assertTrue(engine.query(snapshot).page().hasMore());
    }

    @Test
    void foreignStageOnCommandIsRefusedWithoutLeakingSummary() {
        RequirementStageCommand command = command(
                "cmd-a", "task-a", "CODING_AGENT", "ROLE_EXECUTION:CODING_AGENT", 5_000L);
        AuditRun leak = audit("audit-x", "task-a", "stage-b", "CODING_AGENT", "cmd-a");
        AgentStageRun foreign = stage("stage-b", "task-b", AgentRole.CODING_AGENT, 1);
        CodingMeaSnapshot snapshot = snapshot(
                "task-a",
                "stage-a",
                false,
                state("task-a", 1L, "sha256:" + "e".repeat(64), List.of()),
                Map.of(),
                List.of(stage("stage-a", "task-a", AgentRole.CODING_AGENT, 1), foreign),
                List.of(command),
                Set.of("cmd-a"),
                false,
                "",
                List.of(),
                List.of(leak)
        );

        CodingMeaResponse.CommandReference projected = engine.query(snapshot).commands().getFirst();

        assertNull(projected.stageRunId());
        assertEquals(CodingMeaQueryEngine.NO_UNIQUE_STAGE_BINDING, projected.stageLinkReason());
        assertTrue(engine.query(snapshot).codingStages().stream()
                .noneMatch(stage -> "stage-b".equals(stage.stageRunId())));
    }

    @Test
    void cursorRejectsCrossSelectionReuse() {
        String cursor = CodingMeaCursor.encode("task-a", "stage-a", 10L, "cmd-1");
        assertThrows(CodingMeaBadRequestException.class,
                () -> CodingMeaCursor.decode(cursor, "task-b", "stage-a"));
        assertThrows(CodingMeaBadRequestException.class,
                () -> CodingMeaCursor.decode(cursor, "task-a", "stage-other"));
        assertEquals(10L, CodingMeaCursor.decode(cursor, "task-a", "stage-a").createdAtEpochMillis());
    }

    private static CodingMeaSnapshot snapshot(
            String taskId,
            String codingStageRunId,
            boolean missing,
            AuditedTaskState head,
            Map<Long, AuditedTaskState> revisions,
            List<AgentStageRun> stages,
            List<RequirementStageCommand> commands,
            Set<String> knownCommandIds,
            boolean hasMore,
            String nextCursor,
            List<ManagerDecision> decisions,
            List<AuditRun> audits
    ) {
        return snapshot(
                taskId, codingStageRunId, missing, head, revisions, stages, commands,
                knownCommandIds, hasMore, nextCursor, decisions, audits, List.of(), List.of());
    }

    private static CodingMeaSnapshot snapshot(
            String taskId,
            String codingStageRunId,
            boolean missing,
            AuditedTaskState head,
            Map<Long, AuditedTaskState> revisions,
            List<AgentStageRun> stages,
            List<RequirementStageCommand> commands,
            Set<String> knownCommandIds,
            boolean hasMore,
            String nextCursor,
            List<ManagerDecision> decisions,
            List<AuditRun> audits,
            List<HostVerificationRun> hostVerifications,
            List<com.wish.rd.engine.retry.model.TaskRetryAttemptBinding> bindings
    ) {
        return new CodingMeaSnapshot(
                1_725_600_000_000L,
                taskId,
                8L,
                "EXECUTING",
                false,
                codingStageRunId,
                missing,
                head,
                revisions,
                stages,
                commands,
                knownCommandIds,
                hasMore,
                nextCursor,
                decisions,
                List.of(),
                hostVerifications,
                audits,
                bindings
        );
    }

    private static AgentStageRun stage(String id, String taskId, AgentRole role, int attemptNo) {
        return AgentStageRun.pending(id, taskId, role, attemptNo, taskId + ":" + role + ":" + attemptNo, 1_000L)
                .withStatus(AgentStageStatus.SUCCEEDED, "", "", 1_100L)
                .withResultArtifactId("art-" + id, 1_100L);
    }

    private static RequirementStageCommand command(
            String id, String taskId, String role, String stage, long createdAt
    ) {
        return RequirementStageCommand.pending(
                id, taskId, 3L, 4L, role, stage, 0, 3, createdAt + 60_000L,
                ScheduleResourceClass.GENERIC, Set.of(ScheduleResourceClass.GENERIC),
                "project", "memory", "P1", createdAt);
    }

    private static AuditRun audit(
            String id, String taskId, String stageRunId, String role, String commandId
    ) {
        return new AuditRun(
                id, taskId, stageRunId, role, commandId,
                AuditCompletion.INCOMPLETE, AuditIntegrity.CLEAN, ContractAuditVerdict.ALIGNED,
                List.of(), List.of(), List.of(), List.of(), List.of(), 1_200L);
    }

    private static AuditedTaskState state(
            String taskId, long version, String hash, List<AuditedRecord> records
    ) {
        return new AuditedTaskState(
                taskId,
                version,
                hash,
                new AuditedContractRef("sha256:" + "f".repeat(64), 1L, 1L),
                records,
                "audit-head"
        );
    }

    private static AuditedRecord pendingAc(String id) {
        return new AuditedRecord(
                id, AuditedRecordKind.REQUIREMENT, true, id + " text",
                AuditedRecordStatus.PENDING, List.of(), "", "");
    }
}
