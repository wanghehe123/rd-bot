package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.AgentExecutionProfileSnapshotRow;
import com.wish.rd.bootstrap.persistence.entity.AgentRemediationRoundRow;
import com.wish.rd.bootstrap.persistence.entity.RdAgentStageRunRow;
import com.wish.rd.bootstrap.persistence.entity.RequirementStageCommandRow;
import com.wish.rd.bootstrap.persistence.mapper.AgentExecutionProfileSnapshotMapper;
import com.wish.rd.bootstrap.persistence.mapper.AgentRemediationRoundMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdAgentStageRunMapper;
import com.wish.rd.bootstrap.persistence.mapper.RequirementStageCommandMapper;
import com.wish.rd.engine.requirement.job.RequirementStageExecutionPlanCodec;
import com.wish.rd.engine.requirement.job.model.CommandDisposition;
import com.wish.rd.engine.requirement.job.model.ContinuationSpec;
import com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt;
import com.wish.rd.engine.requirement.job.model.PiQaRemediationIntent;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PiRemediationFinalizationWriterTest {

    @Test
    void shouldPersistTargetsSnapshotsLedgerAndFirstCommandUsingPreallocatedIdentity() {
        AgentRemediationRoundMapper rounds = mock(AgentRemediationRoundMapper.class);
        RdAgentStageRunMapper stages = mock(RdAgentStageRunMapper.class);
        AgentExecutionProfileSnapshotMapper snapshots = mock(AgentExecutionProfileSnapshotMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        PiQaRemediationIntent intent = intent();
        stubStage(stages, 702L, "CODING_AGENT", 2, "pi-remediation:9001:CODING_AGENT");
        stubStage(stages, 703L, "QA_AGENT", 2, "pi-remediation:9001:QA_AGENT");
        stubSnapshot(snapshots, intent.codingProfile());
        stubSnapshot(snapshots, intent.qaProfile());
        AgentRemediationRoundRow round = round(intent);
        when(rounds.findBySourceForUpdate(701L, "QA_PRODUCT_FIX")).thenReturn(null, round);
        when(rounds.findByTaskKindNoForUpdate(700L, "QA_PRODUCT_FIX", 1)).thenReturn(null);
        when(rounds.markDispatched(org.mockito.ArgumentMatchers.eq(9001L),
                org.mockito.ArgumentMatchers.eq(704L), org.mockito.ArgumentMatchers.any())).thenReturn(1);
        RequirementStageCommandRow commandRow = new RequirementStageCommandRow();
        commandRow.id = 704L;
        commandRow.remediationRoundId = 9001L;
        when(commands.findById(704L)).thenReturn(commandRow);

        RequirementStageCommand created = new PiRemediationFinalizationWriter(
                rounds, stages, snapshots, commands).persist(
                intent, plan(intent), sourceCommand(), 100L);

        assertEquals("704", created.commandId());
        assertEquals("9001", created.remediationRoundId());
        assertEquals("CODING_AGENT", created.role());
        assertEquals(intent.sourceStageRunId(), created.remediationSourceStageRunId());
        assertEquals(intent.requestJson(), created.remediationRequestJson());
        assertEquals(intent.requestHash(), created.remediationRequestHash());
        assertEquals(10_099L, created.deadlineEpochMillis(),
                "remediation commands must receive a fresh full deadline window");
        InOrder order = inOrder(rounds, stages, snapshots, commands);
        order.verify(rounds).findBySourceForUpdate(701L, "QA_PRODUCT_FIX");
        order.verify(rounds).findByTaskKindNoForUpdate(700L, "QA_PRODUCT_FIX", 1);
        order.verify(stages).insertIfAbsent(any(RdAgentStageRunRow.class));
        order.verify(snapshots).insertIfAbsent(any(AgentExecutionProfileSnapshotRow.class));
        order.verify(stages).insertIfAbsent(any(RdAgentStageRunRow.class));
        order.verify(snapshots).insertIfAbsent(any(AgentExecutionProfileSnapshotRow.class));
        order.verify(rounds).insertClaimed(any(AgentRemediationRoundRow.class));
        order.verify(commands).enqueue(any(RequirementStageCommandRow.class));
        order.verify(rounds).markDispatched(org.mockito.ArgumentMatchers.eq(9001L),
                org.mockito.ArgumentMatchers.eq(704L), any());
    }

    @Test
    void shouldRejectACompetingSourceForTheSameTaskKindAndRoundBeforeCreatingTargets() {
        AgentRemediationRoundMapper rounds = mock(AgentRemediationRoundMapper.class);
        RdAgentStageRunMapper stages = mock(RdAgentStageRunMapper.class);
        AgentExecutionProfileSnapshotMapper snapshots = mock(AgentExecutionProfileSnapshotMapper.class);
        RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        PiQaRemediationIntent intent = intent();
        AgentRemediationRoundRow competing = round(intent);
        competing.id = 9999L;
        competing.sourceStageRunId = 799L;
        when(rounds.findBySourceForUpdate(701L, "QA_PRODUCT_FIX")).thenReturn(null);
        when(rounds.findByTaskKindNoForUpdate(700L, "QA_PRODUCT_FIX", 1)).thenReturn(competing);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new PiRemediationFinalizationWriter(rounds, stages, snapshots, commands)
                        .persist(intent, plan(intent), sourceCommand(), 100L));

        verify(stages, never()).insertIfAbsent(any());
        verify(snapshots, never()).insertIfAbsent(any());
        verify(commands, never()).enqueue(any());
        verify(rounds, never()).insertClaimed(any());
    }

    private static void stubStage(
            RdAgentStageRunMapper mapper, long id, String role, int attempt, String key
    ) {
        RdAgentStageRunRow row = new RdAgentStageRunRow();
        row.id = id;
        row.taskId = 700L;
        row.role = role;
        row.attemptNo = attempt;
        row.idempotencyKey = key;
        when(mapper.selectById(id)).thenReturn(row);
    }

    private static void stubSnapshot(
            AgentExecutionProfileSnapshotMapper mapper,
            PiQaRemediationIntent.PreparedProfileSnapshot prepared
    ) {
        AgentExecutionProfileSnapshotRow row = new AgentExecutionProfileSnapshotRow();
        row.snapshotId = prepared.snapshotId();
        row.snapshotJson = prepared.snapshotJson();
        row.snapshotHash = prepared.snapshotHash();
        when(mapper.findByStageRunId(Long.parseLong(prepared.stageRunId()))).thenReturn(row);
    }

    private static AgentRemediationRoundRow round(PiQaRemediationIntent intent) {
        AgentRemediationRoundRow row = new AgentRemediationRoundRow();
        row.id = 9001L;
        row.taskId = 700L;
        row.kind = intent.kind().name();
        row.remediationNo = 1;
        row.sourceCommandId = 701L;
        row.requestHash = intent.requestHash();
        row.targetCodingStageRunId = 702L;
        row.targetQaStageRunId = 703L;
        return row;
    }

    private static RequirementStageCommand sourceCommand() {
        return RequirementStageCommand.pending(
                "701", "700", 7L, 3L, "QA_AGENT", "ROLE_EXECUTION:QA_AGENT", 0, 3,
                10_000L, ScheduleResourceClass.BROWSER_QA, "project", "qa-provider", "P1", 1L)
                .claimed("worker", 1000L, 2L);
    }

    private static RequirementStageExecutionPlan plan(PiQaRemediationIntent intent) {
        return new RequirementStageExecutionPlan(
                2, "700", 7L, 3L, RdTaskStatus.EXECUTING, List.of(), CommandDisposition.SUCCEEDED,
                ContinuationSpec.terminal(), ExternalEffectReceipt.none(), intent);
    }

    private static PiQaRemediationIntent intent() {
        PiQaRemediationIntent.ExecutionProfileClaim qa = claim("z-qa", 4L);
        PiQaRemediationIntent.ExecutionProfileClaim coding = claim("a-coding", 5L);
        PiQaRemediationIntent.PreparedProfileSnapshot codingSnapshot = prepared(
                "snapshot-702", "702", "CODING_AGENT", 2, coding);
        PiQaRemediationIntent.PreparedProfileSnapshot qaSnapshot = prepared(
                "snapshot-703", "703", "QA_AGENT", 2, qa);
        String request = "{\"reason\":\"verified defect\"}";
        return new PiQaRemediationIntent(
                PiQaRemediationIntent.PROTOCOL, "700", "701", "701", "sha256:" + "a".repeat(64),
                7L, 3L, AgentRemediationKind.QA_PRODUCT_FIX, 1, "9001",
                request, RequirementStageExecutionPlanCodec.digest(request), "702", 2, "703", 2, "704",
                qa, codingSnapshot, qaSnapshot, "", "");
    }

    private static PiQaRemediationIntent.ExecutionProfileClaim claim(String id, long version) {
        return new PiQaRemediationIntent.ExecutionProfileClaim(
                id, version, "PI", List.of("PI_QA_REMEDIATION_V2"));
    }

    private static PiQaRemediationIntent.PreparedProfileSnapshot prepared(
            String snapshotId, String stageId, String role, int attempt,
            PiQaRemediationIntent.ExecutionProfileClaim claim
    ) {
        String json = "{\"attemptNo\":" + attempt + ",\"capabilities\":[\"PI_QA_REMEDIATION_V2\"],"
                + "\"profileId\":\"" + claim.profileId() + "\",\"profileVersion\":" + claim.profileVersion()
                + ",\"providerProfileId\":\"provider\",\"role\":\"" + role
                + "\",\"runtimeType\":\"PI\",\"stageRunId\":\"" + stageId
                + "\",\"taskId\":\"700\"}";
        return new PiQaRemediationIntent.PreparedProfileSnapshot(
                snapshotId, stageId, role, attempt, claim, json, AgentExecutionProfileSnapshot.sha256(json));
    }
}
