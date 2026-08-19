package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.AgentExecutionProfileRow;
import com.wish.rd.bootstrap.persistence.entity.AgentRemediationRoundRow;
import com.wish.rd.bootstrap.persistence.entity.RequirementStageCommandRow;
import com.wish.rd.bootstrap.persistence.entity.RequirementStageFinalizationRow;
import com.wish.rd.bootstrap.persistence.mapper.AgentExecutionProfileMapper;
import com.wish.rd.bootstrap.persistence.mapper.AgentRemediationRoundMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskStatusEventMapper;
import com.wish.rd.bootstrap.persistence.mapper.RequirementDeliveryJobMapper;
import com.wish.rd.bootstrap.persistence.mapper.RequirementStageCommandMapper;
import com.wish.rd.bootstrap.persistence.mapper.RequirementStageFinalizationMapper;
import com.wish.rd.engine.requirement.job.RequirementStageExecutionPlanCodec;
import com.wish.rd.engine.requirement.job.model.CommandDisposition;
import com.wish.rd.engine.requirement.job.model.ContinuationSpec;
import com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt;
import com.wish.rd.engine.requirement.job.model.PiQaRemediationIntent;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.requirement.job.model.RequirementStageFinalization;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresRequirementStageFinalizationRemediationTest {

    @Test
    void shouldLockMarkerThenDeduplicatedProfilesInAscendingOrderBeforeRecordingOutcome() {
        Fixture fixture = new Fixture();
        when(fixture.markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(fixture.marker));
        when(fixture.profiles.findForUpdate("a-coding")).thenReturn(profileRow("a-coding", 5L));
        when(fixture.profiles.findForUpdate("z-qa")).thenReturn(profileRow("z-qa", 4L));
        when(fixture.commands.lockByIdForUpdate(701L)).thenReturn(commandRow(fixture.command));
        when(fixture.markers.recordOutcomePrepared(any())).thenReturn(1);

        fixture.adapter.recordOutcome(fixture.marker, fixture.command, "worker", fixture.plan, 10L);

        InOrder ordered = inOrder(fixture.markers, fixture.profiles, fixture.commands);
        ordered.verify(fixture.markers).findForUpdate(701L, fixture.marker.attemptNo());
        ordered.verify(fixture.profiles).findForUpdate("a-coding");
        ordered.verify(fixture.profiles).findForUpdate("z-qa");
        ordered.verify(fixture.commands).lockByIdForUpdate(701L);
        ordered.verify(fixture.markers).recordOutcomePrepared(any());
    }

    @Test
    void shouldRejectProfileVersionDriftBeforeCommandLockOrOutcomeMarker() {
        Fixture fixture = new Fixture();
        when(fixture.markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(fixture.marker));
        when(fixture.profiles.findForUpdate("a-coding")).thenReturn(profileRow("a-coding", 6L));

        assertThrows(IllegalStateException.class, () -> fixture.adapter.recordOutcome(
                fixture.marker, fixture.command, "worker", fixture.plan, 10L));

        verify(fixture.commands, never()).lockByIdForUpdate(701L);
        verify(fixture.markers, never()).recordOutcomePrepared(any());
    }

    @Test
    void shouldReassignOccupiedRemediationNoUnderTaskAdvisoryLockBeforeProfileLocks() {
        Fixture fixture = new Fixture();
        AgentRemediationRoundMapper rounds = mock(AgentRemediationRoundMapper.class);
        PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                fixture.markers, fixture.commands, mock(RequirementDeliveryJobMapper.class),
                mock(RdTaskMapper.class), mock(RdTaskStatusEventMapper.class),
                SnowflakeIdGenerator.defaultGenerator(),
                null, null, null, null, null, null, fixture.profiles, null, rounds);
        when(fixture.markers.findForUpdate(anyLong(), anyInt())).thenReturn(markerRow(fixture.marker));
        when(rounds.acquireTaskLock(anyLong())).thenReturn(1);
        when(rounds.findBySourceForUpdate(705L, "QA_PRODUCT_FIX")).thenReturn(null);
        AgentRemediationRoundRow occupied = new AgentRemediationRoundRow();
        occupied.kind = "QA_PRODUCT_FIX";
        occupied.remediationNo = 1;
        when(rounds.listByTask(700L)).thenReturn(List.of(occupied));
        when(fixture.markers.listRecordedOutcomePlans(700L)).thenReturn(List.of());
        when(fixture.profiles.findForUpdate("a-coding")).thenReturn(profileRow("a-coding", 5L));
        when(fixture.profiles.findForUpdate("z-qa")).thenReturn(profileRow("z-qa", 4L));
        when(fixture.commands.lockByIdForUpdate(701L)).thenReturn(commandRow(fixture.command));
        when(fixture.markers.recordOutcomePrepared(any())).thenReturn(1);

        adapter.recordOutcome(fixture.marker, fixture.command, "worker", fixture.plan, 10L);

        org.mockito.ArgumentCaptor<RequirementStageFinalizationRow> recorded =
                org.mockito.ArgumentCaptor.forClass(RequirementStageFinalizationRow.class);
        InOrder ordered = inOrder(fixture.markers, rounds, fixture.profiles);
        ordered.verify(fixture.markers).findForUpdate(701L, fixture.marker.attemptNo());
        ordered.verify(rounds).acquireTaskLock(
                PostgresRequirementStageFinalizationAdapter.REMEDIATION_TASK_LOCK_NAMESPACE ^ 700L);
        ordered.verify(fixture.profiles).findForUpdate("a-coding");
        verify(fixture.markers).recordOutcomePrepared(recorded.capture());
        RequirementStageExecutionPlan frozen = new RequirementStageExecutionPlanCodec()
                .decodeAndVerify(recorded.getValue().outcomePlanJson, recorded.getValue().outcomePlanDigest);
        assertEquals(2, frozen.piQaRemediationIntent().remediationNo());
    }

    private static final class Fixture {
        final RequirementStageFinalizationMapper markers = mock(RequirementStageFinalizationMapper.class);
        final RequirementStageCommandMapper commands = mock(RequirementStageCommandMapper.class);
        final AgentExecutionProfileMapper profiles = mock(AgentExecutionProfileMapper.class);
        final RequirementStageCommand command = RequirementStageCommand.pending(
                "701", "700", 7L, 3L, "QA_AGENT", "ROLE_EXECUTION:QA_AGENT",
                0, 3, 1000L, ScheduleResourceClass.PROVIDER, "project", "provider", "P1", 1L)
                .claimed("worker", 10_000L, 2L);
        final RequirementStageFinalization marker = RequirementStageFinalization.prepared(
                command, RdTaskStatus.EXECUTING, 3L);
        final RequirementStageExecutionPlan plan = new RequirementStageExecutionPlan(
                2, "700", 7L, 3L, RdTaskStatus.EXECUTING, List.of(),
                CommandDisposition.SUCCEEDED, ContinuationSpec.terminal(), ExternalEffectReceipt.none(), intent());
        final PostgresRequirementStageFinalizationAdapter adapter = new PostgresRequirementStageFinalizationAdapter(
                markers, commands, mock(RequirementDeliveryJobMapper.class), mock(RdTaskMapper.class),
                mock(RdTaskStatusEventMapper.class), SnowflakeIdGenerator.defaultGenerator(),
                null, null, null, null, null, null, profiles);
    }

    private static PiQaRemediationIntent intent() {
        PiQaRemediationIntent.ExecutionProfileClaim source = claim("z-qa", 4L);
        PiQaRemediationIntent.ExecutionProfileClaim codingClaim = claim("a-coding", 5L);
        String codingJson = snapshot("702", "CODING_AGENT", 2, codingClaim);
        String qaJson = snapshot("703", "QA_AGENT", 2, source);
        PiQaRemediationIntent.PreparedProfileSnapshot coding = prepared(
                "snapshot-702", "702", "CODING_AGENT", 2, codingClaim, codingJson);
        PiQaRemediationIntent.PreparedProfileSnapshot qa = prepared(
                "snapshot-703", "703", "QA_AGENT", 2, source, qaJson);
        String request = "{\"reason\":\"verified defect\"}";
        return new PiQaRemediationIntent(
                PiQaRemediationIntent.PROTOCOL, "700", "705", "701",
                "sha256:" + "a".repeat(64), 7L, 3L, AgentRemediationKind.QA_PRODUCT_FIX, 1, "9001",
                request, RequirementStageExecutionPlanCodec.digest(request),
                "702", 2, "703", 2, "704", source, coding, qa, "", "");
    }

    private static PiQaRemediationIntent.ExecutionProfileClaim claim(String id, long version) {
        return new PiQaRemediationIntent.ExecutionProfileClaim(
                id, version, "PI", List.of("PI_QA_REMEDIATION_V2"));
    }

    private static PiQaRemediationIntent.PreparedProfileSnapshot prepared(
            String snapshotId, String stageId, String role, int attempt,
            PiQaRemediationIntent.ExecutionProfileClaim claim, String json
    ) {
        return new PiQaRemediationIntent.PreparedProfileSnapshot(
                snapshotId, stageId, role, attempt, claim, json, AgentExecutionProfileSnapshot.sha256(json));
    }

    private static String snapshot(
            String stageId, String role, int attempt, PiQaRemediationIntent.ExecutionProfileClaim claim
    ) {
        return "{\"attemptNo\":" + attempt + ",\"capabilities\":[\"PI_QA_REMEDIATION_V2\"],"
                + "\"profileId\":\"" + claim.profileId() + "\",\"profileVersion\":" + claim.profileVersion()
                + ",\"role\":\"" + role + "\",\"runtimeType\":\"PI\",\"stageRunId\":\""
                + stageId + "\",\"taskId\":\"700\"}";
    }

    private static AgentExecutionProfileRow profileRow(String id, long version) {
        AgentExecutionProfileRow row = new AgentExecutionProfileRow();
        row.profileId = id;
        row.version = version;
        row.runtimeType = "PI";
        row.enabled = true;
        row.capabilitiesJson = "[\"PI_QA_REMEDIATION_V2\"]";
        return row;
    }

    private static RequirementStageFinalizationRow markerRow(RequirementStageFinalization marker) {
        RequirementStageFinalizationRow row = new RequirementStageFinalizationRow();
        row.commandId = Long.valueOf(marker.commandId());
        row.attemptNo = marker.attemptNo();
        row.taskId = Long.valueOf(marker.taskId());
        row.expectedTaskVersion = marker.expectedTaskVersion();
        row.expectedFencingToken = marker.expectedFencingToken();
        row.expectedTaskStatus = marker.expectedTaskStatus().name();
        row.stage = marker.stage();
        row.state = marker.state().name();
        row.outcomeStatus = "";
        row.resultJson = "";
        row.errorMessage = "";
        row.outcomePlanJson = "";
        row.outcomePlanDigest = "";
        row.preparedAt = OffsetDateTime.now();
        return row;
    }

    private static RequirementStageCommandRow commandRow(RequirementStageCommand command) {
        RequirementStageCommandRow row = new RequirementStageCommandRow();
        row.id = Long.valueOf(command.commandId());
        row.taskId = Long.valueOf(command.taskId());
        row.taskVersion = command.taskVersion();
        row.fencingToken = command.fencingToken();
        row.role = command.role();
        row.stage = command.stage();
        row.attemptNo = command.attemptNo();
        row.maxAttempts = command.maxAttempts();
        row.resourceClass = command.resourceClass().name();
        row.resourceRequirements = command.encodedResourceRequirements();
        row.projectId = command.projectId();
        row.providerId = command.providerId();
        row.priorityRank = command.priorityRank();
        row.status = command.status().name();
        row.leaseOwner = command.leaseOwner();
        row.leaseUntil = OffsetDateTime.now().plusMinutes(1);
        row.nextVisibleAt = OffsetDateTime.now();
        row.lastError = "";
        row.createdAt = OffsetDateTime.now();
        row.updatedAt = OffsetDateTime.now();
        row.businessGeneration = 0L;
        return row;
    }
}
