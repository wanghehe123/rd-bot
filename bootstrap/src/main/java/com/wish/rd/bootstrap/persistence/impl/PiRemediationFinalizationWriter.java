package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.AgentExecutionProfileSnapshotRow;
import com.wish.rd.bootstrap.persistence.entity.AgentRemediationRoundRow;
import com.wish.rd.bootstrap.persistence.entity.RdAgentStageRunRow;
import com.wish.rd.bootstrap.persistence.entity.RequirementStageCommandRow;
import com.wish.rd.bootstrap.persistence.mapper.AgentExecutionProfileSnapshotMapper;
import com.wish.rd.bootstrap.persistence.mapper.AgentRemediationRoundMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdAgentStageRunMapper;
import com.wish.rd.bootstrap.persistence.mapper.RequirementStageCommandMapper;
import com.wish.rd.engine.requirement.job.model.PiQaRemediationIntent;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.scheduling.FairRequirementDeliveryClaimPlanner;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Set;

/** Writes all target objects for one recorded PI remediation intent inside the caller transaction. */
@Component
@ConditionalOnBean(AgentRemediationRoundMapper.class)
public class PiRemediationFinalizationWriter {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentRemediationRoundMapper roundMapper;
    private final RdAgentStageRunMapper stageMapper;
    private final AgentExecutionProfileSnapshotMapper snapshotMapper;
    private final RequirementStageCommandMapper commandMapper;

    public PiRemediationFinalizationWriter(
            AgentRemediationRoundMapper roundMapper,
            RdAgentStageRunMapper stageMapper,
            AgentExecutionProfileSnapshotMapper snapshotMapper,
            RequirementStageCommandMapper commandMapper
    ) {
        this.roundMapper = java.util.Objects.requireNonNull(roundMapper);
        this.stageMapper = java.util.Objects.requireNonNull(stageMapper);
        this.snapshotMapper = java.util.Objects.requireNonNull(snapshotMapper);
        this.commandMapper = java.util.Objects.requireNonNull(commandMapper);
    }

    /**
     * Claims the unique round, materializes target stages/snapshots, and enqueues the first command.
     *
     * @param intent frozen intent whose {@code remNo} was assigned at {@code recordOutcome}
     * @param plan recorded execution plan
     * @param source current QA command being finalized
     * @param nowEpochMillis Host timestamp
     * @return the first continuation command
     */
    public RequirementStageCommand persist(
            PiQaRemediationIntent intent,
            RequirementStageExecutionPlan plan,
            RequirementStageCommand source,
            long nowEpochMillis
    ) {
        OffsetDateTime now = PostgresPersistenceSupport.toDateTime(nowEpochMillis);
        AgentRemediationRoundRow requestedRound = roundRow(intent, now);
        AgentRemediationRoundRow existingSource = roundMapper.findBySourceForUpdate(
                PostgresPersistenceSupport.parseId(intent.sourceStageRunId()), intent.kind().name());
        AgentRemediationRoundRow effectiveRound;
        if (existingSource == null) {
            AgentRemediationRoundRow occupied = roundMapper.findByTaskKindNoForUpdate(
                    PostgresPersistenceSupport.parseId(intent.sourceTaskId()),
                    intent.kind().name(),
                    intent.remediationNo());
            if (occupied != null) {
                throw new IllegalStateException("conflicting remediation round already claimed: "
                        + intent.kind() + "#" + intent.remediationNo());
            }
        } else {
            requireExactRound(requestedRound, existingSource);
        }
        if (intent.codingProfile() != null) {
            persistStage(intent.sourceTaskId(), intent.codingProfile(), intent.roundId(), now);
            persistSnapshot(intent.sourceTaskId(), intent.codingProfile(), now);
        }
        persistStage(intent.sourceTaskId(), intent.qaProfile(), intent.roundId(), now);
        persistSnapshot(intent.sourceTaskId(), intent.qaProfile(), now);

        if (existingSource == null) {
            roundMapper.insertClaimed(requestedRound);
            effectiveRound = roundMapper.findBySourceForUpdate(
                    PostgresPersistenceSupport.parseId(intent.sourceStageRunId()), intent.kind().name());
            requireExactRound(requestedRound, effectiveRound);
        } else {
            effectiveRound = existingSource;
        }

        PiQaRemediationIntent.PreparedProfileSnapshot firstTarget = intent.codingProfile() == null
                ? intent.qaProfile() : intent.codingProfile();
        RequirementStageCommand firstCommand = firstCommand(intent, plan, source, firstTarget, nowEpochMillis);
        commandMapper.enqueue(commandRow(firstCommand));
        RequirementStageCommandRow effectiveCommand = commandMapper.findById(
                PostgresPersistenceSupport.parseId(firstCommand.commandId()));
        if (effectiveCommand == null || effectiveCommand.remediationRoundId == null
                || !intent.roundId().equals(effectiveCommand.remediationRoundId.toString())) {
            throw new IllegalStateException("remediation first command identity conflict: " + firstCommand.commandId());
        }
        if (effectiveRound.firstCommandId == null) {
            if (roundMapper.markDispatched(effectiveRound.id,
                    PostgresPersistenceSupport.parseId(firstCommand.commandId()), now) != 1) {
                throw new IllegalStateException("remediation round dispatch compare-and-set failed: " + intent.roundId());
            }
        } else if (!firstCommand.commandId().equals(effectiveRound.firstCommandId.toString())) {
            throw new IllegalStateException("remediation round first command conflict: " + intent.roundId());
        }
        return firstCommand;
    }

    private void persistStage(
            String taskId,
            PiQaRemediationIntent.PreparedProfileSnapshot target,
            String roundId,
            OffsetDateTime now
    ) {
        RdAgentStageRunRow row = new RdAgentStageRunRow();
        row.id = PostgresPersistenceSupport.parseId(target.stageRunId());
        row.taskId = PostgresPersistenceSupport.parseId(taskId);
        row.role = target.role();
        row.status = "PENDING";
        row.attemptNo = target.attemptNo();
        row.idempotencyKey = "pi-remediation:" + roundId + ":" + target.role();
        row.providerName = target.profile().profileId();
        row.providerAttemptsJson = "[]";
        row.reviewResultJson = "{}";
        row.errorCategory = "";
        row.errorMessage = "";
        row.createdAt = now;
        row.updatedAt = now;
        stageMapper.insertIfAbsent(row);
        RdAgentStageRunRow stored = stageMapper.selectById(row.id);
        if (stored == null || !row.taskId.equals(stored.taskId) || !row.role.equals(stored.role)
                || !row.attemptNo.equals(stored.attemptNo) || !row.idempotencyKey.equals(stored.idempotencyKey)) {
            throw new IllegalStateException("remediation target stage identity conflict: " + target.stageRunId());
        }
    }

    private void persistSnapshot(
            String taskId,
            PiQaRemediationIntent.PreparedProfileSnapshot target,
            OffsetDateTime now
    ) {
        AgentExecutionProfileSnapshotRow row = new AgentExecutionProfileSnapshotRow();
        row.snapshotId = target.snapshotId();
        row.stageRunId = PostgresPersistenceSupport.parseId(target.stageRunId());
        row.taskId = PostgresPersistenceSupport.parseId(taskId);
        row.role = target.role();
        row.attemptNo = target.attemptNo();
        row.runtimeType = target.profile().runtimeType();
        row.snapshotJson = target.snapshotJson();
        row.snapshotHash = target.snapshotHash();
        row.resolvedAt = now;
        snapshotMapper.insertIfAbsent(row);
        AgentExecutionProfileSnapshotRow stored = snapshotMapper.findByStageRunId(row.stageRunId);
        if (stored == null || !row.snapshotId.equals(stored.snapshotId)
                || !row.snapshotHash.equals(stored.snapshotHash) || !row.snapshotJson.equals(stored.snapshotJson)) {
            throw new IllegalStateException("remediation profile snapshot identity conflict: " + target.snapshotId());
        }
    }

    private static AgentRemediationRoundRow roundRow(PiQaRemediationIntent intent, OffsetDateTime now) {
        AgentRemediationRoundRow row = new AgentRemediationRoundRow();
        row.id = PostgresPersistenceSupport.parseId(intent.roundId());
        row.taskId = PostgresPersistenceSupport.parseId(intent.sourceTaskId());
        row.kind = intent.kind().name();
        row.remediationNo = intent.remediationNo();
        row.sourceStageRunId = PostgresPersistenceSupport.parseId(intent.sourceStageRunId());
        row.sourceCommandId = PostgresPersistenceSupport.parseId(intent.sourceCommandId());
        row.sourceResultHash = intent.sourceResultHash();
        row.sourceTaskVersion = intent.sourceTaskVersion();
        row.sourceFencingToken = intent.sourceFencingToken();
        row.targetCodingStageRunId = intent.targetCodingStageRunId().isBlank()
                ? null : PostgresPersistenceSupport.parseId(intent.targetCodingStageRunId());
        row.targetQaStageRunId = PostgresPersistenceSupport.parseId(intent.targetQaStageRunId());
        row.codingProfileSnapshotId = intent.codingProfile() == null ? null : intent.codingProfile().snapshotId();
        row.qaProfileSnapshotId = intent.qaProfile().snapshotId();
        row.requestJson = intent.requestJson();
        row.requestHash = intent.requestHash();
        row.createdAt = now;
        row.updatedAt = now;
        row.claimedAt = now;
        return row;
    }

    private static RequirementStageCommand firstCommand(
            PiQaRemediationIntent intent,
            RequirementStageExecutionPlan plan,
            RequirementStageCommand source,
            PiQaRemediationIntent.PreparedProfileSnapshot target,
            long now
    ) {
        String stage = "ROLE_EXECUTION:" + target.role();
        Set<ScheduleResourceClass> requirements =
                FairRequirementDeliveryClaimPlanner.classifyStageRequirements(target.role(), stage);
        ScheduleResourceClass primary = requirements.contains(ScheduleResourceClass.BROWSER_QA)
                ? ScheduleResourceClass.BROWSER_QA : ScheduleResourceClass.PROVIDER;
        return RequirementStageCommand.remediationPending(
                intent.firstCommandId(), source.taskId(), plan.postVersion(), plan.postFencingToken(),
                target.role(), stage, source.maxAttempts(), refreshedDeadline(source, now), primary, requirements,
                source.projectId(), providerProfileId(target.snapshotJson()), "P" + source.priorityRank(),
                source.policyRunId(), intent.roundId(), intent.kind(), intent.remediationNo(),
                intent.sourceStageRunId(), intent.requestJson(), intent.requestHash(), now);
    }

    private static long refreshedDeadline(RequirementStageCommand source, long nowEpochMillis) {
        long originalWindow = source.deadlineEpochMillis() > source.createdAtEpochMillis()
                ? source.deadlineEpochMillis() - source.createdAtEpochMillis()
                : 1L;
        long now = Math.max(0L, nowEpochMillis);
        return now > Long.MAX_VALUE - originalWindow ? Long.MAX_VALUE : now + originalWindow;
    }

    private static String providerProfileId(String snapshotJson) {
        try {
            JsonNode value = MAPPER.readTree(snapshotJson);
            String provider = value.path("providerProfileId").asText("");
            return provider.isBlank() ? value.path("profileId").asText("pi") : provider;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("invalid prepared target snapshot", invalid);
        }
    }

    private static RequirementStageCommandRow commandRow(RequirementStageCommand command) {
        RequirementStageCommandRow row = new RequirementStageCommandRow();
        row.id = PostgresPersistenceSupport.parseId(command.commandId());
        row.taskId = PostgresPersistenceSupport.parseId(command.taskId());
        row.taskVersion = command.taskVersion();
        row.fencingToken = command.fencingToken();
        row.role = command.role();
        row.stage = command.stage();
        row.policyRunId = command.policyRunId().isBlank() ? null : PostgresPersistenceSupport.parseId(command.policyRunId());
        row.businessGeneration = 0L;
        row.remediationRoundId = PostgresPersistenceSupport.parseId(command.remediationRoundId());
        row.remediationKind = command.remediationKind().name();
        row.remediationNo = command.remediationNo();
        row.remediationSourceStageRunId = PostgresPersistenceSupport.parseId(
                command.remediationSourceStageRunId());
        row.remediationRequestJson = command.remediationRequestJson();
        row.remediationRequestHash = command.remediationRequestHash();
        row.attemptNo = 0;
        row.maxAttempts = command.maxAttempts();
        row.deadlineAt = command.deadlineEpochMillis() <= 0 ? null
                : PostgresPersistenceSupport.toDateTime(command.deadlineEpochMillis());
        row.resourceClass = command.resourceClass().name();
        row.resourceRequirements = command.encodedResourceRequirements();
        row.projectId = command.projectId();
        row.providerId = command.providerId();
        row.priorityRank = command.priorityRank();
        row.status = "PENDING";
        row.leaseOwner = "";
        row.nextVisibleAt = PostgresPersistenceSupport.toDateTime(command.nextVisibleAtEpochMillis());
        row.lastError = "";
        row.createdAt = PostgresPersistenceSupport.toDateTime(command.createdAtEpochMillis());
        row.updatedAt = row.createdAt;
        return row;
    }

    private static void requireExactRound(AgentRemediationRoundRow expected, AgentRemediationRoundRow actual) {
        if (actual == null || !expected.id.equals(actual.id) || !expected.taskId.equals(actual.taskId)
                || !expected.kind.equals(actual.kind) || !expected.remediationNo.equals(actual.remediationNo)
                || !expected.sourceCommandId.equals(actual.sourceCommandId)
                || !expected.requestHash.equals(actual.requestHash)
                || !expected.targetQaStageRunId.equals(actual.targetQaStageRunId)
                || !java.util.Objects.equals(expected.targetCodingStageRunId, actual.targetCodingStageRunId)) {
            throw new IllegalStateException("conflicting remediation replay for source stage");
        }
    }
}
