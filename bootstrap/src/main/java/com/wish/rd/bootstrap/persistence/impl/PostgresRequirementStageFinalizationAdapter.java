package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.RdAgentStageRunRow;
import com.wish.rd.bootstrap.persistence.entity.RequirementDeliveryJobRow;
import com.wish.rd.bootstrap.persistence.entity.RequirementPolicyRunRow;
import com.wish.rd.bootstrap.persistence.entity.RequirementStageCommandRow;
import com.wish.rd.bootstrap.persistence.entity.RequirementStageFinalizationRow;
import com.wish.rd.bootstrap.persistence.entity.RequirementPublicationRow;
import com.wish.rd.bootstrap.persistence.entity.RdTaskRow;
import com.wish.rd.bootstrap.persistence.entity.RdTaskStatusEventRow;
import com.wish.rd.bootstrap.persistence.entity.TaskFailureProvenanceRow;
import com.wish.rd.bootstrap.persistence.entity.TaskRetryAttemptBindingRow;
import com.wish.rd.bootstrap.persistence.entity.TaskRetryCheckpointRow;
import com.wish.rd.bootstrap.persistence.mapper.RequirementDeliveryJobMapper;
import com.wish.rd.bootstrap.persistence.mapper.RequirementPolicyRunMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdAgentStageRunMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskStatusEventMapper;
import com.wish.rd.bootstrap.persistence.mapper.RequirementStageCommandMapper;
import com.wish.rd.bootstrap.persistence.mapper.RequirementStageFinalizationMapper;
import com.wish.rd.bootstrap.persistence.mapper.RequirementPublicationMapper;
import com.wish.rd.bootstrap.persistence.mapper.TaskFailureProvenanceMapper;
import com.wish.rd.bootstrap.persistence.mapper.TaskRetryAttemptBindingMapper;
import com.wish.rd.bootstrap.persistence.mapper.TaskRetryCheckpointMapper;
import com.wish.rd.engine.requirement.job.RequirementStageFinalizationPort;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJob;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.job.model.RequirementStageFinalization;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.requirement.job.model.RequirementTaskMutation;
import com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskRetryFailureProvenance;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL transaction boundary for stage outcome markers, command completion, continuation,
 * and the legacy umbrella job.
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresRequirementStageFinalizationAdapter implements RequirementStageFinalizationPort {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .disable(MapperFeature.AUTO_DETECT_IS_GETTERS);

    private final RequirementStageFinalizationMapper finalizationMapper;
    private final RequirementStageCommandMapper stageCommandMapper;
    private final RequirementDeliveryJobMapper jobMapper;
    private final RdTaskMapper taskMapper;
    private final RdTaskStatusEventMapper taskStatusEventMapper;
    private final SnowflakeIdGenerator eventIdGenerator;
    private final RequirementPublicationMapper publicationMapper;
    private final RequirementPolicyRunMapper policyRunMapper;
    private final RdAgentStageRunMapper agentStageRunMapper;
    private final TaskFailureProvenanceMapper failureProvenanceMapper;
    private final TaskRetryCheckpointMapper checkpointMapper;
    private final TaskRetryAttemptBindingMapper attemptBindingMapper;

    /**
     * Creates the PostgreSQL finalization transaction adapter.
     *
     * @param finalizationMapper marker mapper
     * @param stageCommandMapper stage command mapper
     * @param jobMapper umbrella job mapper
     * @param taskMapper fenced task snapshot mapper
     * @param taskStatusEventMapper append-only task timeline mapper
     */
    public PostgresRequirementStageFinalizationAdapter(
            RequirementStageFinalizationMapper finalizationMapper,
            RequirementStageCommandMapper stageCommandMapper,
            RequirementDeliveryJobMapper jobMapper,
            RdTaskMapper taskMapper,
            RdTaskStatusEventMapper taskStatusEventMapper
    ) {
        this(finalizationMapper, stageCommandMapper, jobMapper, taskMapper, taskStatusEventMapper,
                SnowflakeIdGenerator.defaultGenerator());
    }

    /**
     * Creates the PostgreSQL finalization transaction adapter with a shared event identifier source.
     *
     * @param finalizationMapper marker mapper
     * @param stageCommandMapper stage command mapper
     * @param jobMapper umbrella job mapper
     * @param taskMapper fenced task snapshot mapper
     * @param taskStatusEventMapper append-only task timeline mapper
     * @param eventIdGenerator globally unique task-event identifier source
     */
    public PostgresRequirementStageFinalizationAdapter(
            RequirementStageFinalizationMapper finalizationMapper,
            RequirementStageCommandMapper stageCommandMapper,
            RequirementDeliveryJobMapper jobMapper,
            RdTaskMapper taskMapper,
            RdTaskStatusEventMapper taskStatusEventMapper,
            SnowflakeIdGenerator eventIdGenerator
    ) {
        this(finalizationMapper, stageCommandMapper, jobMapper, taskMapper, taskStatusEventMapper,
                eventIdGenerator, null, null, null, null,
                (TaskRetryCheckpointMapper) null, (TaskRetryAttemptBindingMapper) null);
    }

    /** Creates a focused finalizer with publication-receipt support but no policy continuation. */
    public PostgresRequirementStageFinalizationAdapter(
            RequirementStageFinalizationMapper finalizationMapper,
            RequirementStageCommandMapper stageCommandMapper,
            RequirementDeliveryJobMapper jobMapper,
            RdTaskMapper taskMapper,
            RdTaskStatusEventMapper taskStatusEventMapper,
            SnowflakeIdGenerator eventIdGenerator,
            RequirementPublicationMapper publicationMapper
    ) {
        this(finalizationMapper, stageCommandMapper, jobMapper, taskMapper, taskStatusEventMapper,
                eventIdGenerator, publicationMapper, null, null, null,
                (TaskRetryCheckpointMapper) null, (TaskRetryAttemptBindingMapper) null);
    }

    /** Creates a focused finalizer with policy-ledger continuation support. */
    public PostgresRequirementStageFinalizationAdapter(
            RequirementStageFinalizationMapper finalizationMapper,
            RequirementStageCommandMapper stageCommandMapper,
            RequirementDeliveryJobMapper jobMapper,
            RdTaskMapper taskMapper,
            RdTaskStatusEventMapper taskStatusEventMapper,
            SnowflakeIdGenerator eventIdGenerator,
            RequirementPublicationMapper publicationMapper,
            RequirementPolicyRunMapper policyRunMapper
    ) {
        this(finalizationMapper, stageCommandMapper, jobMapper, taskMapper, taskStatusEventMapper,
                eventIdGenerator, publicationMapper, policyRunMapper, null, null,
                (TaskRetryCheckpointMapper) null, (TaskRetryAttemptBindingMapper) null);
    }

    /**
     * Creates a focused finalizer with provenance support but without checkpoint settlement mappers.
     *
     * @param agentStageRunMapper agent-stage attempt mapper used by role-failure provenance
     * @param failureProvenanceMapper structured failure provenance writer
     */
    public PostgresRequirementStageFinalizationAdapter(
            RequirementStageFinalizationMapper finalizationMapper,
            RequirementStageCommandMapper stageCommandMapper,
            RequirementDeliveryJobMapper jobMapper,
            RdTaskMapper taskMapper,
            RdTaskStatusEventMapper taskStatusEventMapper,
            SnowflakeIdGenerator eventIdGenerator,
            RequirementPublicationMapper publicationMapper,
            RequirementPolicyRunMapper policyRunMapper,
            RdAgentStageRunMapper agentStageRunMapper,
            TaskFailureProvenanceMapper failureProvenanceMapper
    ) {
        this(finalizationMapper, stageCommandMapper, jobMapper, taskMapper, taskStatusEventMapper,
                eventIdGenerator, publicationMapper, policyRunMapper, agentStageRunMapper,
                failureProvenanceMapper,
                (TaskRetryCheckpointMapper) null, (TaskRetryAttemptBindingMapper) null);
    }

    /**
     * Creates the production finalizer with atomic technical-exhaustion settlement.
     *
     * <p>Checkpoint and binding mappers are optional so focused Spring tests and legacy memory
     * profiles can still construct the adapter; exhaustion fails closed when they are absent and a
     * command carries a {@code retryCheckpointId}.
     *
     * @param checkpointMapperProvider exact checkpoint settlement mapper
     * @param attemptBindingMapperProvider checkpoint-scoped attempt binding mapper
     */
    @Autowired
    public PostgresRequirementStageFinalizationAdapter(
            RequirementStageFinalizationMapper finalizationMapper,
            RequirementStageCommandMapper stageCommandMapper,
            RequirementDeliveryJobMapper jobMapper,
            RdTaskMapper taskMapper,
            RdTaskStatusEventMapper taskStatusEventMapper,
            SnowflakeIdGenerator eventIdGenerator,
            RequirementPublicationMapper publicationMapper,
            RequirementPolicyRunMapper policyRunMapper,
            RdAgentStageRunMapper agentStageRunMapper,
            TaskFailureProvenanceMapper failureProvenanceMapper,
            ObjectProvider<TaskRetryCheckpointMapper> checkpointMapperProvider,
            ObjectProvider<TaskRetryAttemptBindingMapper> attemptBindingMapperProvider
    ) {
        this(finalizationMapper, stageCommandMapper, jobMapper, taskMapper, taskStatusEventMapper,
                eventIdGenerator, publicationMapper, policyRunMapper, agentStageRunMapper,
                failureProvenanceMapper,
                checkpointMapperProvider == null ? null : checkpointMapperProvider.getIfAvailable(),
                attemptBindingMapperProvider == null ? null : attemptBindingMapperProvider.getIfAvailable());
    }

    /**
     * Creates the production finalizer with explicit exhaustion mappers for focused tests.
     *
     * @param checkpointMapper exact checkpoint settlement mapper
     * @param attemptBindingMapper checkpoint-scoped attempt binding mapper
     */
    public PostgresRequirementStageFinalizationAdapter(
            RequirementStageFinalizationMapper finalizationMapper,
            RequirementStageCommandMapper stageCommandMapper,
            RequirementDeliveryJobMapper jobMapper,
            RdTaskMapper taskMapper,
            RdTaskStatusEventMapper taskStatusEventMapper,
            SnowflakeIdGenerator eventIdGenerator,
            RequirementPublicationMapper publicationMapper,
            RequirementPolicyRunMapper policyRunMapper,
            RdAgentStageRunMapper agentStageRunMapper,
            TaskFailureProvenanceMapper failureProvenanceMapper,
            TaskRetryCheckpointMapper checkpointMapper,
            TaskRetryAttemptBindingMapper attemptBindingMapper
    ) {
        this.finalizationMapper = Objects.requireNonNull(finalizationMapper, "finalizationMapper must not be null");
        this.stageCommandMapper = Objects.requireNonNull(stageCommandMapper, "stageCommandMapper must not be null");
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper must not be null");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper must not be null");
        this.taskStatusEventMapper = Objects.requireNonNull(
                taskStatusEventMapper, "taskStatusEventMapper must not be null");
        this.eventIdGenerator = Objects.requireNonNull(eventIdGenerator, "eventIdGenerator must not be null");
        this.publicationMapper = publicationMapper;
        this.policyRunMapper = policyRunMapper;
        this.agentStageRunMapper = agentStageRunMapper;
        this.failureProvenanceMapper = failureProvenanceMapper;
        this.checkpointMapper = checkpointMapper;
        this.attemptBindingMapper = attemptBindingMapper;
    }

    /**
     * Persists a pre-execution marker while validating the running command lease.
     *
     * @param command currently leased command
     * @param leaseOwner expected lease owner
     * @param nowEpochMillis Host timestamp
     * @return durable prepared marker
     */
    @Override
    @Transactional
    public RequirementStageFinalization prepare(
            RequirementStageCommand command,
            String leaseOwner,
            RdTaskStatus expectedTaskStatus,
            long nowEpochMillis
    ) {
        requireCommand(command);
        String owner = requireOwner(leaseOwner);
        requireOwnedRunningAttempt(command, owner, nowEpochMillis);
        int inserted = finalizationMapper.insertPreparedIfOwned(
                PostgresPersistenceSupport.parseId(command.commandId()),
                command.attemptNo(),
                owner,
                Objects.requireNonNull(expectedTaskStatus, "expectedTaskStatus must not be null").name(),
                PostgresPersistenceSupport.toDateTime(nowEpochMillis)
        );
        RequirementStageFinalizationRow row = finalizationMapper.findForUpdate(
                PostgresPersistenceSupport.parseId(command.commandId()), command.attemptNo());
        if (row == null) {
            throw new IllegalStateException("stage command lease is not owned: " + command.commandId());
        }
        RequirementStageFinalization marker = toFinalization(row);
        if (!marker.matches(command)) {
            throw new IllegalStateException("stage finalization identity mismatch: " + command.commandId());
        }
        if (inserted == 0 && !marker.isPrepared()) {
            throw new IllegalStateException("stage finalization is already closed: " + command.commandId());
        }
        return marker;
    }

    /**
     * Reads the newest unfinished marker for recovery.
     *
     * @param commandId stage command id
     * @return newest prepared marker when present
     */
    @Override
    public Optional<RequirementStageFinalization> findLatestPrepared(String commandId) {
        String normalized = safe(commandId);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(finalizationMapper.findLatestPrepared(
                PostgresPersistenceSupport.parseId(normalized))).map(this::toFinalization);
    }

    /**
     * Saves the immutable execution plan before the final transaction mutates the task snapshot.
     *
     * @param marker prepared marker
     * @param stageCommand current leased command
     * @param leaseOwner current lease owner
     * @param plan non-mutating execution proposal
     * @param nowEpochMillis Host timestamp
     * @return outcome-recorded marker
     */
    @Override
    @Transactional
    public RequirementStageFinalization recordOutcome(
            RequirementStageFinalization marker,
            RequirementStageCommand stageCommand,
            String leaseOwner,
            RequirementStageExecutionPlan plan,
            long nowEpochMillis
    ) {
        Objects.requireNonNull(marker, "marker must not be null");
        Objects.requireNonNull(stageCommand, "stageCommand must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        String owner = requireOwner(leaseOwner);
        if (!marker.isPrepared() || !marker.matchesCommandIdentity(stageCommand)) {
            throw new IllegalStateException("stage finalization marker is not prepared: " + stageCommand.commandId());
        }
        requireOwnedRunningAttempt(stageCommand, owner, nowEpochMillis);
        requirePlanIdentity(marker, plan);
        RequirementStageFinalizationRow current = finalizationMapper.findForUpdate(
                PostgresPersistenceSupport.parseId(marker.commandId()), marker.attemptNo());
        if (current == null || !"PREPARED".equals(current.state)) {
            throw new IllegalStateException("stage finalization marker is stale: " + marker.commandId());
        }
        RequirementStageFinalization locked = toFinalization(current);
        if (!marker.samePreparedIdentity(locked)) {
            throw new IllegalStateException("stage finalization marker changed: " + marker.commandId());
        }
        // The marker was admitted under this owner; the command completion path rechecks it with
        // the full lease predicate before the task mutation is allowed to commit.
        String planJson = RequirementPolicyRun.canonicalizeJson(encodePlan(plan));
        RequirementStageFinalization recorded = marker.outcomeRecorded(
                plan.postStatus(), planJson, RequirementPolicyRun.canonicalJsonDigest(planJson), nowEpochMillis);
        if (finalizationMapper.recordOutcomePrepared(toRow(recorded)) != 1) {
            throw new IllegalStateException("stage outcome recording compare-and-set failed: " + marker.commandId());
        }
        return recorded;
    }

    @Override
    public RequirementStageExecutionPlan decodeOutcomePlan(RequirementStageFinalization marker) {
        Objects.requireNonNull(marker, "marker must not be null");
        if (!marker.isOutcomeRecorded() || marker.outcomePlanJson().isBlank()) {
            throw new IllegalStateException("stage marker has no recorded plan: " + marker.commandId());
        }
        String canonical = RequirementPolicyRun.canonicalizeJson(marker.outcomePlanJson());
        if (!RequirementPolicyRun.canonicalJsonDigest(canonical).equals(marker.outcomePlanDigest())) {
            throw new IllegalStateException("stage outcome plan digest mismatch: " + marker.commandId());
        }
        try {
            RequirementStageExecutionPlan plan = OBJECT_MAPPER.readValue(canonical, RequirementStageExecutionPlan.class);
            requirePlanIdentity(marker, plan);
            if (marker.outcomeStatus() != plan.postStatus()) {
                throw new IllegalStateException("stage outcome plan post-status conflicts with marker: "
                        + marker.commandId());
            }
            return plan;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stage outcome plan cannot be decoded: " + marker.commandId(), exception);
        }
    }

    /**
     * Finalizes a stage outcome, its current command, continuation, and umbrella job in one
     * PostgreSQL transaction. Any command CAS failure raises and rolls back every following write.
     *
     * @param command immutable finalization input
     * @return durable finalization result
     */
    @Override
    @Transactional
    public FinalizationResult finalize(FinalizationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        RequirementStageFinalization marker = command.marker();
        RequirementStageCommand stageCommand = command.stageCommand();
        if (!marker.isOutcomeRecorded() || !marker.matchesCommandIdentity(stageCommand)) {
            throw new IllegalStateException("stage finalization marker is not recoverable: "
                    + stageCommand.commandId());
        }
        requireOwnedRunningAttempt(stageCommand, requireOwner(command.leaseOwner()), command.nowEpochMillis());
        RequirementStageFinalizationRow currentMarker = finalizationMapper.findForUpdate(
                PostgresPersistenceSupport.parseId(marker.commandId()), marker.attemptNo());
        if (currentMarker == null || !"OUTCOME_RECORDED".equals(currentMarker.state)) {
            throw new IllegalStateException("stage finalization marker is stale: " + marker.commandId());
        }
        RequirementStageFinalization lockedMarker = toFinalization(currentMarker);
        if (!marker.sameOutcomeRecordedIdentity(lockedMarker)) {
            throw new IllegalStateException("stage finalization marker changed: " + marker.commandId());
        }

        boolean deferRetryableMutation = defersRetryableMutation(command);
        applyTaskMutation(command, lockedMarker);
        RequirementPublicationRow publicationReceipt = lockPublicationReceipt(command.plan(), marker.taskId());

        // The completion CAS is deliberately before continuation visibility. Because this method
        // is transactional, its failure rolls back the task/event write above as well.
        RequirementStageCommandRow completedRow = command.plan().commandDisposition()
                == com.wish.rd.engine.requirement.job.model.CommandDisposition.RETRYABLE_TECHNICAL_FAILURE
                ? stageCommandMapper.failAttempt(
                        PostgresPersistenceSupport.parseId(stageCommand.commandId()),
                        stageCommand.attemptNo(),
                        requireOwner(command.leaseOwner()),
                        command.outcome().errorMessage(),
                        PostgresPersistenceSupport.toDateTime(command.nowEpochMillis()),
                        PostgresPersistenceSupport.toDateTime(command.nowEpochMillis()))
                : stageCommandMapper.completeAttempt(
                        PostgresPersistenceSupport.parseId(stageCommand.commandId()),
                        stageCommand.attemptNo(),
                        requireOwner(command.leaseOwner()),
                        PostgresPersistenceSupport.toDateTime(command.nowEpochMillis()));
        if (completedRow == null) {
            throw new IllegalStateException("stage command completion lease update failed: "
                    + stageCommand.commandId());
        }
        RequirementStageCommand completed = toCommand(completedRow);

        RequirementStageCommand next = null;
        if (completed.status() == RequirementStageCommand.Status.SUCCEEDED && command.nextCommand() != null) {
            preparePolicyEvaluateLedger(command.plan(), command.nextCommand(), command.nowEpochMillis());
            stageCommandMapper.enqueue(toRow(command.nextCommand()));
            RequirementStageCommandRow nextRow = stageCommandMapper.findByIdentity(
                    PostgresPersistenceSupport.parseId(command.nextCommand().taskId()),
                    command.nextCommand().role(),
                    command.nextCommand().stage(), command.nextCommand().retryCheckpointId());
            if (nextRow == null) {
                throw new IllegalStateException("stage continuation was not persisted: "
                        + command.nextCommand().commandId());
            }
            next = toCommand(nextRow);
            requireExactContinuationIdentity(command.nextCommand(), next);
        }

        applyJobDisposition(command);
        commitPublicationReceipt(publicationReceipt, command.nowEpochMillis());
        if (deferRetryableMutation) {
            return new FinalizationResult(marker, completed, next);
        }
        RequirementStageFinalization finalized = marker.finalized(
                command.outcome(), next == null ? "" : next.commandId(), command.nowEpochMillis());
        if (finalizationMapper.finalizePrepared(toRow(finalized)) != 1) {
            throw new IllegalStateException("stage finalization compare-and-set failed: " + marker.commandId());
        }
        recordAgentRoleFailureProvenance(command, finalized);
        recordPublicationFailureProvenance(command, finalized, deferRetryableMutation);
        return new FinalizationResult(finalized, completed, next);
    }

    /**
     * Atomically terminalizes one technically exhausted stage command under the documented lock
     * order: source policy (policy-control only) -> command -> task -> checkpoint ->
     * bindings/attempts. Provenance is stamped from the real post-CAS task snapshot, never a
     * predicted {@code version+1}/{@code fence+1}. An already-{@code DEAD_LETTERED} command is
     * resumable; when task failure and provenance are already durable the call is a no-op.
     *
     * @param command exhaustion input
     * @return durable command, task, provenance, and optional settled checkpoint
     */
    @Override
    @Transactional
    public ExhaustionResult exhaustCommand(ExhaustionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (failureProvenanceMapper == null) {
            throw new UnsupportedOperationException("stage command exhaustion is not configured: "
                    + command.stageCommand().commandId());
        }
        RequirementStageCommand stageCommand = command.stageCommand();
        String owner = requireOwner(command.leaseOwner());
        ExhaustionProvenanceDraft draft = command.provenanceDraft();
        if (isPolicyControlStage(stageCommand.stage()) && !draft.sourcePolicyRunId().isBlank()) {
            if (policyRunMapper == null) {
                throw new IllegalStateException(
                        "policy-control exhaustion requires a policy-run mapper: " + stageCommand.commandId());
            }
            RequirementPolicyRunRow policy = policyRunMapper.lockForUpdate(
                    PostgresPersistenceSupport.parseId(draft.sourcePolicyRunId()));
            if (policy == null) {
                throw new IllegalStateException("exhaustion source policy run not found: "
                        + draft.sourcePolicyRunId());
            }
        }

        RequirementStageCommandRow lockedCommand = stageCommandMapper.lockByIdForUpdate(
                PostgresPersistenceSupport.parseId(stageCommand.commandId()));
        if (lockedCommand == null) {
            throw new IllegalStateException("stage command lease is not owned: " + stageCommand.commandId());
        }
        RequirementStageCommand currentCommand = toCommand(lockedCommand);
        boolean resumeDeadLettered = currentCommand.status() == RequirementStageCommand.Status.DEAD_LETTERED;
        if (currentCommand.status() == RequirementStageCommand.Status.SUCCEEDED
                || currentCommand.status() == RequirementStageCommand.Status.CANCELLED) {
            throw new IllegalStateException("stage command is already terminal: " + currentCommand.commandId());
        }
        if (!resumeDeadLettered) {
            if (currentCommand.status() != RequirementStageCommand.Status.RUNNING
                    || currentCommand.attemptNo() != stageCommand.attemptNo()
                    || !currentCommand.leaseOwner().equals(owner)
                    || currentCommand.leaseUntilEpochMillis() <= command.nowEpochMillis()) {
                throw new IllegalStateException("stage command lease is not owned: " + stageCommand.commandId());
            }
        } else if (currentCommand.attemptNo() != stageCommand.attemptNo()) {
            throw new IllegalStateException("exhausted command attempt identity mismatch: "
                    + stageCommand.commandId());
        }

        RequirementStageCommand completedCommand;
        if (resumeDeadLettered) {
            completedCommand = currentCommand;
        } else {
            RequirementStageCommandRow failedRow = stageCommandMapper.failAttempt(
                    PostgresPersistenceSupport.parseId(stageCommand.commandId()),
                    stageCommand.attemptNo(),
                    owner,
                    command.errorMessage(),
                    PostgresPersistenceSupport.toDateTime(command.nowEpochMillis()),
                    PostgresPersistenceSupport.toDateTime(command.nowEpochMillis()));
            if (failedRow == null) {
                throw new IllegalStateException("stage command exhaustion lease update failed: "
                        + stageCommand.commandId());
            }
            completedCommand = toCommand(failedRow);
        }
        if (completedCommand.status() != RequirementStageCommand.Status.DEAD_LETTERED) {
            throw new IllegalStateException("exhausted command did not become DEAD_LETTERED: "
                    + completedCommand.commandId());
        }

        RdTaskRow lockedTask = taskMapper.lockByIdForUpdate(
                PostgresPersistenceSupport.parseId(stageCommand.taskId()));
        if (lockedTask == null) {
            throw new IllegalStateException("stage task snapshot is missing: " + stageCommand.taskId());
        }
        if (!RdRequirementTask.TASK_TYPE.equals(safe(lockedTask.taskType))) {
            throw new IllegalStateException("stage task snapshot is not a requirement task: "
                    + stageCommand.taskId());
        }
        long currentVersion = lockedTask.version == null ? 0L : lockedTask.version;
        long currentFence = lockedTask.fencingToken == null ? 0L : lockedTask.fencingToken;
        RdTaskStatus currentStatus = RdTaskStatus.valueOf(safe(lockedTask.status));
        if (resumeDeadLettered && isTerminalFailureStatus(currentStatus)) {
            return resumeAlreadyDurableExhaustion(completedCommand, lockedTask, currentStatus,
                    currentVersion, currentFence);
        }
        if (!command.expectedTaskStatus().name().equals(safe(lockedTask.status))
                || currentVersion != command.expectedTaskVersion()
                || currentFence != command.expectedTaskFencingToken()) {
            throw new IllegalStateException("exhaustion task snapshot mismatch: " + stageCommand.taskId());
        }
        // Paths that never prepared (expired lease / rejected queue / fail-before-plan) still need a
        // recoverable marker so authoritative findExact corroboration can accept the provenance.
        if (!isPolicyControlStage(completedCommand.stage())) {
            finalizationMapper.insertPreparedForExhaustedAttempt(
                    PostgresPersistenceSupport.parseId(completedCommand.commandId()),
                    completedCommand.attemptNo(),
                    command.expectedTaskStatus().name(),
                    PostgresPersistenceSupport.toDateTime(command.nowEpochMillis()));
        }
        int taskChanged = taskMapper.advanceStatusWithExpectedVersionFenced(
                PostgresPersistenceSupport.parseId(stageCommand.taskId()),
                command.expectedTaskVersion(),
                command.expectedTaskFencingToken(),
                command.expectedTaskStatus().name(),
                command.targetTaskStatus().name(),
                command.errorMessage(),
                command.resultJson(),
                null,
                null,
                PostgresPersistenceSupport.toDateTime(command.nowEpochMillis()));
        if (taskChanged != 1) {
            throw new IllegalStateException("exhaustion task snapshot compare-and-set failed: "
                    + stageCommand.taskId());
        }
        RdTaskStatusEventRow event = new RdTaskStatusEventRow();
        event.id = eventIdGenerator.nextId();
        event.taskId = PostgresPersistenceSupport.parseId(stageCommand.taskId());
        event.status = command.targetTaskStatus().name();
        event.title = safe(lockedTask.title);
        event.message = command.errorMessage();
        event.enteredAt = PostgresPersistenceSupport.toDateTime(command.nowEpochMillis());
        event.durationMs = 0L;
        event.trigger = "SYSTEM";
        if (taskStatusEventMapper.insert(event) != 1) {
            throw new IllegalStateException("exhaustion task timeline append failed: " + stageCommand.taskId());
        }
        RdTaskRow failedTaskRow = taskMapper.selectById(PostgresPersistenceSupport.parseId(stageCommand.taskId()));
        if (failedTaskRow == null) {
            throw new IllegalStateException("exhaustion task snapshot missing after CAS: "
                    + stageCommand.taskId());
        }
        RdRequirementTask failedTask = toRequirementTask(failedTaskRow);

        TaskFailureProvenanceRow provenanceRow = new TaskFailureProvenanceRow();
        provenanceRow.id = PostgresPersistenceSupport.parseId(draft.provenanceId());
        provenanceRow.taskId = PostgresPersistenceSupport.parseId(failedTask.taskId());
        provenanceRow.failedStageCommandId = PostgresPersistenceSupport.parseId(completedCommand.commandId());
        provenanceRow.failedCommandAttemptNo = completedCommand.attemptNo();
        provenanceRow.failedStage = draft.failedStage();
        provenanceRow.failurePhase = draft.failurePhase().name();
        provenanceRow.outcomeStatus = failedTask.status().name();
        provenanceRow.failedTaskVersion = failedTask.version();
        provenanceRow.failedTaskFencingToken = failedTask.fencingToken();
        provenanceRow.failedStageRunId = blankToNullId(draft.failedStageRunId());
        provenanceRow.failedRetrievalRunId = blankToNullId(draft.failedRetrievalRunId());
        provenanceRow.failedAiReviewRunId = blankToNullId(draft.failedAiReviewRunId());
        provenanceRow.sourcePolicyRunId = blankToNullId(draft.sourcePolicyRunId());
        provenanceRow.sourcePlanDigest = draft.sourcePlanDigest();
        provenanceRow.publicationOperationId = draft.publicationOperationId().isBlank()
                ? null : draft.publicationOperationId();
        provenanceRow.failureKind = draft.failureKind();
        provenanceRow.recordedAt = PostgresPersistenceSupport.toDateTime(command.nowEpochMillis());
        if (failureProvenanceMapper.insertIfAbsent(provenanceRow) != 1) {
            throw new IllegalStateException("failure provenance identity conflict: " + draft.provenanceId());
        }
        TaskRetryFailureProvenance provenance = new TaskRetryFailureProvenance(
                draft.provenanceId(),
                failedTask.taskId(),
                completedCommand.commandId(),
                completedCommand.attemptNo(),
                draft.failedStage(),
                draft.failurePhase(),
                failedTask.status(),
                failedTask.version(),
                failedTask.fencingToken(),
                draft.failedStageRunId(),
                draft.failedRetrievalRunId(),
                draft.failedAiReviewRunId(),
                draft.sourcePolicyRunId(),
                draft.sourcePlanDigest(),
                draft.publicationOperationId(),
                draft.failureKind(),
                command.nowEpochMillis());

        TaskRetryCheckpoint settledCheckpoint = settleExhaustionCheckpoint(completedCommand, command);
        return new ExhaustionResult(completedCommand, failedTask, provenance, settledCheckpoint);
    }

    private ExhaustionResult resumeAlreadyDurableExhaustion(
            RequirementStageCommand completedCommand,
            RdTaskRow lockedTask,
            RdTaskStatus currentStatus,
            long currentVersion,
            long currentFence
    ) {
        TaskFailureProvenanceRow existing = failureProvenanceMapper.findByCommandAttempt(
                PostgresPersistenceSupport.parseId(completedCommand.commandId()),
                completedCommand.attemptNo());
        if (existing == null) {
            existing = failureProvenanceMapper.findExact(
                    lockedTask.id, currentStatus.name(), currentVersion, currentFence);
        }
        if (existing == null) {
            throw new IllegalStateException(
                    "terminal task lacks durable failure provenance for exhausted command: "
                            + completedCommand.commandId());
        }
        RdRequirementTask failedTask = toRequirementTask(lockedTask);
        TaskRetryFailureProvenance provenance = new TaskRetryFailureProvenance(
                String.valueOf(existing.id),
                failedTask.taskId(),
                completedCommand.commandId(),
                completedCommand.attemptNo(),
                safe(existing.failedStage),
                TaskFailurePhase.valueOf(safe(existing.failurePhase)),
                failedTask.status(),
                failedTask.version(),
                failedTask.fencingToken(),
                existing.failedStageRunId == null ? "" : String.valueOf(existing.failedStageRunId),
                existing.failedRetrievalRunId == null ? "" : String.valueOf(existing.failedRetrievalRunId),
                existing.failedAiReviewRunId == null ? "" : String.valueOf(existing.failedAiReviewRunId),
                existing.sourcePolicyRunId == null ? "" : String.valueOf(existing.sourcePolicyRunId),
                safe(existing.sourcePlanDigest),
                safe(existing.publicationOperationId),
                safe(existing.failureKind),
                existing.recordedAt == null ? 0L : existing.recordedAt.toInstant().toEpochMilli());
        TaskRetryCheckpoint settledCheckpoint = null;
        String checkpointId = completedCommand.retryCheckpointId();
        if (!checkpointId.isBlank() && checkpointMapper != null) {
            TaskRetryCheckpointRow row = checkpointMapper.lockByIdForUpdate(
                    PostgresPersistenceSupport.parseId(checkpointId));
            if (row != null) {
                settledCheckpoint = toCheckpoint(row);
            }
        }
        return new ExhaustionResult(completedCommand, failedTask, provenance, settledCheckpoint);
    }

    private TaskRetryCheckpoint settleExhaustionCheckpoint(
            RequirementStageCommand completedCommand,
            ExhaustionCommand command
    ) {
        String checkpointId = completedCommand.retryCheckpointId();
        if (checkpointId.isBlank()) {
            return null;
        }
        if (checkpointMapper == null || attemptBindingMapper == null || agentStageRunMapper == null) {
            throw new IllegalStateException(
                    "checkpoint-bound exhaustion requires checkpoint, binding, and stage mappers: "
                            + checkpointId);
        }
        TaskRetryCheckpointRow lockedCheckpoint = checkpointMapper.lockByIdForUpdate(
                PostgresPersistenceSupport.parseId(checkpointId));
        if (lockedCheckpoint == null) {
            throw new IllegalStateException("retry checkpoint not found: " + checkpointId);
        }
        TaskRetryCheckpointStatus currentStatus = parseCheckpointStatus(lockedCheckpoint.status);
        if (currentStatus.isTerminal()) {
            return toCheckpoint(lockedCheckpoint);
        }
        if (currentStatus != TaskRetryCheckpointStatus.DISPATCHED
                && currentStatus != TaskRetryCheckpointStatus.WAITING_APPROVAL) {
            throw new IllegalStateException("retry checkpoint is not settleable: " + checkpointId);
        }
        TaskRetryCheckpointStatus targetStatus = resolveExhaustionCheckpointTarget(
                currentStatus, command.targetCheckpointStatus(), checkpointId);
        TaskRetryCheckpointRow transition = new TaskRetryCheckpointRow();
        transition.id = lockedCheckpoint.id;
        transition.status = targetStatus.name();
        transition.expectedStatus = currentStatus.name();
        transition.errorMessage = command.errorMessage();
        transition.updatedAt = PostgresPersistenceSupport.toDateTime(command.nowEpochMillis());
        if (checkpointMapper.transition(transition) != 1) {
            throw new IllegalStateException("retry checkpoint compare-and-set failed: " + checkpointId);
        }
        lockedCheckpoint.status = targetStatus.name();
        lockedCheckpoint.errorMessage = command.errorMessage();
        lockedCheckpoint.updatedAt = transition.updatedAt;
        cancelPendingBoundAttempts(checkpointId, command.nowEpochMillis());
        return toCheckpoint(lockedCheckpoint);
    }

    private static boolean isTerminalFailureStatus(RdTaskStatus status) {
        return status == RdTaskStatus.FAILED_RETRYABLE
                || status == RdTaskStatus.FAILED_NEEDS_HUMAN
                || status == RdTaskStatus.DEAD_LETTERED
                || status == RdTaskStatus.REJECTED
                || status == RdTaskStatus.CANCELLED;
    }

    /**
     * Chooses a legal terminal checkpoint status for exhaustion.
     *
     * <p>{@code WAITING_APPROVAL -> FAILED_RETRYABLE} is rejected by the store matrix. Remap that
     * requested target to {@code FAILED_NEEDS_HUMAN} so the checkpoint can settle without rolling
     * back the already-chosen command/task terminalization.
     */
    private static TaskRetryCheckpointStatus resolveExhaustionCheckpointTarget(
            TaskRetryCheckpointStatus currentStatus,
            TaskRetryCheckpointStatus requestedTarget,
            String checkpointId
    ) {
        if (requestedTarget == null || !requestedTarget.isTerminal()) {
            throw new IllegalStateException("exhaustion checkpoint target must be terminal: " + checkpointId);
        }
        if (currentStatus == TaskRetryCheckpointStatus.WAITING_APPROVAL
                && requestedTarget == TaskRetryCheckpointStatus.FAILED_RETRYABLE) {
            return TaskRetryCheckpointStatus.FAILED_NEEDS_HUMAN;
        }
        boolean allowed = (currentStatus == TaskRetryCheckpointStatus.DISPATCHED
                && (requestedTarget == TaskRetryCheckpointStatus.SUCCEEDED
                || requestedTarget == TaskRetryCheckpointStatus.FAILED_RETRYABLE
                || requestedTarget == TaskRetryCheckpointStatus.FAILED_NEEDS_HUMAN
                || requestedTarget == TaskRetryCheckpointStatus.CANCELLED))
                || (currentStatus == TaskRetryCheckpointStatus.WAITING_APPROVAL
                && (requestedTarget == TaskRetryCheckpointStatus.FAILED_NEEDS_HUMAN
                || requestedTarget == TaskRetryCheckpointStatus.CANCELLED));
        if (!allowed) {
            throw new IllegalStateException("illegal exhaustion checkpoint transition: "
                    + currentStatus + " -> " + requestedTarget + " for " + checkpointId);
        }
        return requestedTarget;
    }

    private void cancelPendingBoundAttempts(String checkpointId, long nowEpochMillis) {
        List<TaskRetryAttemptBindingRow> bindings = attemptBindingMapper.listByCheckpoint(
                PostgresPersistenceSupport.parseId(checkpointId));
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        for (TaskRetryAttemptBindingRow binding : bindings) {
            if (binding == null || !"AGENT_STAGE".equals(safe(binding.bindingKind))
                    || binding.stageRunId == null) {
                continue;
            }
            RdAgentStageRunRow attempt = agentStageRunMapper.selectById(binding.stageRunId);
            if (attempt == null || !"PENDING".equals(safe(attempt.status))) {
                continue;
            }
            RdAgentStageRunRow updated = new RdAgentStageRunRow();
            updated.id = attempt.id;
            updated.taskId = attempt.taskId;
            updated.role = attempt.role;
            updated.status = "CANCELLED";
            updated.attemptNo = attempt.attemptNo;
            updated.idempotencyKey = attempt.idempotencyKey;
            updated.providerName = attempt.providerName;
            updated.providerAttemptsJson = attempt.providerAttemptsJson;
            updated.contextPackageId = attempt.contextPackageId;
            updated.promptArtifactId = attempt.promptArtifactId;
            updated.resultArtifactId = attempt.resultArtifactId;
            updated.reviewResultJson = attempt.reviewResultJson;
            updated.errorCategory = "RETRY_CHECKPOINT_EXHAUSTED";
            updated.errorMessage = "unused pending attempt cancelled with exhausted checkpoint: "
                    + checkpointId;
            updated.startedAt = attempt.startedAt;
            updated.finishedAt = PostgresPersistenceSupport.toDateTime(nowEpochMillis);
            updated.createdAt = attempt.createdAt;
            updated.updatedAt = PostgresPersistenceSupport.toDateTime(nowEpochMillis);
            if (agentStageRunMapper.updateStageRunWhenStatusMatches(updated, "PENDING") != 1) {
                throw new IllegalStateException("pending attempt was not cancelled: " + attempt.id);
            }
        }
    }

    private static boolean isPolicyControlStage(String stage) {
        String normalized = safe(stage);
        return "POLICY_EVALUATE".equals(normalized)
                || "POLICY_APPLY".equals(normalized)
                || "APPROVAL_RESUME".equals(normalized);
    }

    private static Long blankToNullId(String value) {
        String normalized = safe(value);
        return normalized.isBlank() ? null : PostgresPersistenceSupport.parseId(normalized);
    }

    private static TaskRetryCheckpointStatus parseCheckpointStatus(String value) {
        try {
            return TaskRetryCheckpointStatus.valueOf(safe(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("unknown retry checkpoint status: " + safe(value), exception);
        }
    }

    private TaskRetryCheckpoint toCheckpoint(TaskRetryCheckpointRow row) {
        return new TaskRetryCheckpoint(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.taskId),
                parseFailurePhase(row.failurePhase),
                null,
                PostgresPersistenceSupport.idString(row.failedStageRunId),
                PostgresPersistenceSupport.idString(row.failedRetrievalRunId),
                PostgresPersistenceSupport.idString(row.failedAiReviewRunId),
                row.attemptNo == null ? 1 : row.attemptNo,
                safe(row.idempotencyKey),
                parseExpectedTaskStatus(row.sourceTaskStatus),
                row.sourceTaskVersion == null ? 0L : row.sourceTaskVersion,
                row.sourceFencingToken == null ? 0L : row.sourceFencingToken,
                PostgresPersistenceSupport.idString(row.failedStageCommandId),
                safe(row.failedStage),
                PostgresPersistenceSupport.idString(row.sourcePolicyRunId),
                PostgresPersistenceSupport.idString(row.policyRunId),
                safe(row.sourcePlanDigest),
                safe(row.authorizationPlanDigest),
                safe(row.publicationOperationId),
                row.dispatchTaskVersion == null ? 0L : row.dispatchTaskVersion,
                row.dispatchFencingToken == null ? 0L : row.dispatchFencingToken,
                PostgresPersistenceSupport.idString(row.dispatchCommandId),
                row.businessGeneration == null ? 0L : row.businessGeneration,
                safe(row.operatorNote),
                List.of(),
                parseCheckpointStatus(row.status),
                safe(row.reason),
                safe(row.errorMessage),
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }

    private static TaskFailurePhase parseFailurePhase(String value) {
        try {
            return TaskFailurePhase.valueOf(safe(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("unknown failure phase: " + safe(value), exception);
        }
    }

    private RdRequirementTask toRequirementTask(RdTaskRow row) {
        return new RdRequirementTask(
                PostgresPersistenceSupport.idString(row.id),
                safe(row.taskType),
                safe(row.sourceType),
                safe(row.sourceId),
                safe(row.sourceUrl),
                safe(row.priority),
                RdTaskStatus.valueOf(safe(row.status)),
                safe(row.title),
                safe(row.projectId),
                safe(row.projectKey),
                safe(row.projectName),
                safe(row.repositoryUrl),
                safe(row.repoOwner),
                safe(row.repoName),
                safe(row.baseBranch),
                safe(row.workBranch),
                safe(row.expectedResult),
                safe(row.acceptanceCriteriaJson),
                safe(row.promptSnapshot),
                safe(row.executionResultJson),
                safe(row.pullRequestUrl),
                safe(row.errorMessage),
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt),
                row.paused != null && row.paused,
                row.tokenBudgetOverride == null ? 0L : row.tokenBudgetOverride,
                row.version == null ? 0L : row.version,
                row.fencingToken == null ? 0L : row.fencingToken,
                null
        );
    }

    /**
     * Records the exact terminal role failure in the same transaction that makes its command and
     * outcome marker durable. PostgreSQL retry resolution intentionally has no heuristic fallback,
     * so omitting this snapshot would make a real, terminal role failure non-retryable.
     */
    private void recordAgentRoleFailureProvenance(
            FinalizationCommand command, RequirementStageFinalization finalized
    ) {
        RequirementStageCommand stageCommand = command.stageCommand();
        if (command.taskMutationDisposition() != TaskMutationDisposition.APPLY
                || !stageCommand.stage().startsWith("ROLE_EXECUTION:")
                || (finalized.outcomeStatus() != RdTaskStatus.FAILED_RETRYABLE
                && finalized.outcomeStatus() != RdTaskStatus.FAILED_NEEDS_HUMAN)) {
            return;
        }
        if (failureProvenanceMapper == null || agentStageRunMapper == null || policyRunMapper == null
                || stageCommand.policyRunId().isBlank()) {
            throw new IllegalStateException("terminal role failure provenance persistence is unavailable: "
                    + stageCommand.commandId());
        }
        String role = stageCommand.stage().substring("ROLE_EXECUTION:".length()).strip();
        if (role.isBlank() || !role.equals(stageCommand.role())) {
            throw new IllegalStateException("terminal role failure command has an invalid stage: "
                    + stageCommand.commandId());
        }
        RequirementPolicyRunRow policy = policyRunMapper.findById(
                PostgresPersistenceSupport.parseId(stageCommand.policyRunId()));
        if (policy == null || policy.taskId == null
                || policy.taskId.longValue() != PostgresPersistenceSupport.parseId(stageCommand.taskId())
                || safe(policy.planDigest).isBlank()) {
            throw new IllegalStateException("terminal role failure has no matching policy generation: "
                    + stageCommand.commandId());
        }
        List<RdAgentStageRunRow> failedRuns = agentStageRunMapper.selectList(
                new QueryWrapper<RdAgentStageRunRow>()
                        .eq("task_id", policy.taskId)
                        .eq("role", role)
                        .in("status", RdTaskStatus.FAILED_RETRYABLE.name(), RdTaskStatus.FAILED_NEEDS_HUMAN.name())
                        .orderByDesc("attempt_no")
                        .orderByDesc("updated_at")
                        .orderByDesc("id")
                        .last("LIMIT 1"));
        if (failedRuns == null || failedRuns.size() != 1 || failedRuns.getFirst().id == null) {
            throw new IllegalStateException("terminal role failure has no exact stage run: "
                    + stageCommand.commandId());
        }
        RdAgentStageRunRow failedRun = failedRuns.getFirst();
        TaskFailureProvenanceRow row = new TaskFailureProvenanceRow();
        row.id = eventIdGenerator.nextId();
        row.taskId = policy.taskId;
        row.failedStageCommandId = PostgresPersistenceSupport.parseId(stageCommand.commandId());
        row.failedCommandAttemptNo = stageCommand.attemptNo();
        row.failedStage = stageCommand.stage();
        row.failurePhase = "AGENT_ROLE";
        row.outcomeStatus = finalized.outcomeStatus().name();
        row.failedTaskVersion = command.plan().postVersion();
        row.failedTaskFencingToken = command.plan().postFencingToken();
        row.failedStageRunId = failedRun.id;
        row.sourcePolicyRunId = policy.id;
        row.sourcePlanDigest = safe(policy.planDigest);
        row.failureKind = safe(failedRun.errorCategory);
        row.recordedAt = PostgresPersistenceSupport.toDateTime(command.nowEpochMillis());
        if (failureProvenanceMapper.insertIfAbsent(row) != 1) {
            throw new IllegalStateException("terminal role failure provenance conflicts: " + stageCommand.commandId());
        }
    }

    private void recordPublicationFailureProvenance(
            FinalizationCommand command,
            RequirementStageFinalization finalized,
            boolean deferRetryableMutation
    ) {
        if (deferRetryableMutation) {
            return;
        }
        RequirementStageCommand stageCommand = command.stageCommand();
        String stage = stageCommand.stage();
        if (!"PUBLICATION".equals(stage) && !stage.startsWith("PUBLICATION:")) {
            return;
        }
        if (finalized.outcomeStatus() != RdTaskStatus.FAILED_RETRYABLE
                && finalized.outcomeStatus() != RdTaskStatus.FAILED_NEEDS_HUMAN) {
            return;
        }
        if (failureProvenanceMapper == null || publicationMapper == null || policyRunMapper == null) {
            throw new IllegalStateException("terminal publication failure provenance persistence is unavailable: "
                    + stageCommand.commandId());
        }
        ExternalEffectReceipt receipt = command.plan().externalEffectReceipt();
        if (receipt.kind() != ExternalEffectReceipt.Kind.PUBLICATION || receipt.operationId().isBlank()) {
            throw new IllegalStateException("terminal publication failure requires a publication receipt: "
                    + stageCommand.commandId());
        }
        if (stageCommand.policyRunId().isBlank()) {
            throw new IllegalStateException("terminal publication failure has no policy generation: "
                    + stageCommand.commandId());
        }
        RequirementPublicationRow publication = publicationMapper.selectByOperationIdForUpdate(receipt.operationId());
        if (publication == null
                || publication.taskId == null
                || publication.taskId.longValue() != PostgresPersistenceSupport.parseId(stageCommand.taskId())) {
            throw new IllegalStateException("terminal publication failure has no matching ledger row: "
                    + stageCommand.commandId());
        }
        RequirementPolicyRunRow policy = policyRunMapper.findById(
                PostgresPersistenceSupport.parseId(stageCommand.policyRunId()));
        if (policy == null || policy.taskId == null
                || policy.taskId.longValue() != PostgresPersistenceSupport.parseId(stageCommand.taskId())
                || safe(policy.planDigest).isBlank()) {
            throw new IllegalStateException("terminal publication failure has no matching policy generation: "
                    + stageCommand.commandId());
        }
        String failureKind = safe(publication.status);
        if (failureKind.isBlank()) {
            failureKind = receipt.durableState();
        }
        TaskFailureProvenanceRow row = new TaskFailureProvenanceRow();
        row.id = eventIdGenerator.nextId();
        row.taskId = policy.taskId;
        row.failedStageCommandId = PostgresPersistenceSupport.parseId(stageCommand.commandId());
        row.failedCommandAttemptNo = stageCommand.attemptNo();
        row.failedStage = "PUBLICATION:" + receipt.operationId();
        row.failurePhase = "PR_PUBLICATION";
        row.outcomeStatus = finalized.outcomeStatus().name();
        row.failedTaskVersion = command.plan().postVersion();
        row.failedTaskFencingToken = command.plan().postFencingToken();
        row.sourcePolicyRunId = policy.id;
        row.sourcePlanDigest = safe(policy.planDigest);
        row.publicationOperationId = receipt.operationId();
        row.failureKind = failureKind;
        row.recordedAt = PostgresPersistenceSupport.toDateTime(command.nowEpochMillis());
        if (failureProvenanceMapper.insertIfAbsent(row) != 1) {
            throw new IllegalStateException("terminal publication failure provenance conflicts: "
                    + stageCommand.commandId());
        }
    }

    /**
     * Creates the frozen PLAN_READY generation before its FK-bound evaluate command is inserted.
     *
     * <p>The finalizer is already the sole transactional owner of this continuation. Keeping the
     * ledger insert here ensures a dispatcher cannot observe a policy command without the exact
     * generation that fences its later decision.
     */
    private void preparePolicyEvaluateLedger(
            RequirementStageExecutionPlan plan, RequirementStageCommand continuation, long nowEpochMillis
    ) {
        if (!"POLICY_EVALUATE".equals(continuation.stage())) {
            return;
        }
        if (policyRunMapper == null) {
            throw new IllegalStateException("policy-evaluate continuation requires a policy-run mapper");
        }
        if (!continuation.commandId().equals(continuation.policyRunId())
                || !plan.taskId().equals(continuation.taskId())
                || continuation.taskVersion() != plan.postVersion()
                || continuation.fencingToken() != plan.postFencingToken()
                || continuation.status() != RequirementStageCommand.Status.PENDING
                || !continuation.leaseOwner().isBlank()
                || continuation.leaseUntilEpochMillis() != 0L
                || !"REQUIREMENT_DELIVERY".equals(continuation.role())
                || plan.postStatus() != RdTaskStatus.PLAN_GENERATED
                || plan.mutations().isEmpty()) {
            throw new IllegalStateException("policy-evaluate continuation identity is invalid: "
                    + continuation.commandId());
        }
        String frozenPlanJson = RequirementPolicyRun.canonicalizeJson(
                plan.mutations().getLast().executionResultJson());
        RequirementPolicyRun requested = RequirementPolicyEvaluationPreparationSupport.planReadyLedger(
                continuation, frozenPlanJson, RequirementPolicyRun.canonicalJsonDigest(frozenPlanJson), nowEpochMillis);
        RequirementPolicyRunRow before = policyRunMapper.findById(
                PostgresPersistenceSupport.parseId(requested.id()));
        if (before == null) {
            policyRunMapper.insertIfAbsent(PostgresRequirementPolicyRunStore.toRow(requested));
        }
        RequirementPolicyRunRow persisted = policyRunMapper.findById(
                PostgresPersistenceSupport.parseId(requested.id()));
        if (persisted == null || !RequirementPolicyEvaluationPreparationSupport.samePreparedLedgerGeneration(
                PostgresRequirementPolicyRunStore.toModel(persisted), requested)) {
            throw new IllegalStateException("policy-evaluate ledger conflicts: " + continuation.commandId());
        }
    }

    private static void requireExactContinuationIdentity(
            RequirementStageCommand requested,
            RequirementStageCommand effective
    ) {
        if (effective == null
                || effective.fencingToken() <= 0L
                || !requested.commandId().equals(effective.commandId())
                || !requested.taskId().equals(effective.taskId())
                || requested.taskVersion() != effective.taskVersion()
                || requested.fencingToken() != effective.fencingToken()
                || !requested.role().equals(effective.role())
                || !requested.stage().equals(effective.stage())
                || requested.maxAttempts() != effective.maxAttempts()
                || requested.deadlineEpochMillis() != effective.deadlineEpochMillis()
                || requested.resourceClass() != effective.resourceClass()
                || !requested.resourceRequirements().equals(effective.resourceRequirements())
                || !requested.projectId().equals(effective.projectId())
                || !requested.providerId().equals(effective.providerId())
                || requested.priorityRank() != effective.priorityRank()
                || !requested.policyRunId().equals(effective.policyRunId())) {
            throw new IllegalStateException(
                    "stage continuation conflict has a different durable identity: "
                            + requested.commandId());
        }
    }

    private static void requirePlanIdentity(
            RequirementStageFinalization marker,
            RequirementStageExecutionPlan plan
    ) {
        if (!marker.taskId().equals(plan.taskId())
                || marker.expectedTaskVersion() != plan.expectedVersion()
                || marker.expectedFencingToken() != plan.expectedFencingToken()
                || marker.expectedTaskStatus() != plan.expectedStatus()
                || !plan.externalEffectReceipt().allowsOutcomeRecording(plan.commandDisposition())) {
            throw new IllegalStateException("stage outcome plan conflicts with prepared marker: " + marker.commandId());
        }
    }

    private void requireOwnedRunningAttempt(
            RequirementStageCommand command, String owner, long nowEpochMillis
    ) {
        RequirementStageCommandRow current = stageCommandMapper.lockByIdForUpdate(
                PostgresPersistenceSupport.parseId(command.commandId()));
        if (current == null) {
            throw new IllegalStateException("stage command lease is not owned: " + command.commandId());
        }
        RequirementStageCommand locked = toCommand(current);
        if (locked.status() != RequirementStageCommand.Status.RUNNING
                || locked.attemptNo() != command.attemptNo()
                || !locked.leaseOwner().equals(owner)
                || locked.leaseUntilEpochMillis() <= nowEpochMillis) {
            throw new IllegalStateException("stage command lease is not owned: " + command.commandId());
        }
    }

    private RequirementPublicationRow lockPublicationReceipt(
            RequirementStageExecutionPlan plan, String taskId
    ) {
        ExternalEffectReceipt receipt = plan.externalEffectReceipt();
        if (receipt.kind() != ExternalEffectReceipt.Kind.PUBLICATION || !receipt.isFinalizable()) {
            return null;
        }
        if (publicationMapper == null) {
            throw new IllegalStateException("publication receipt finalization is unavailable");
        }
        long expectedTaskId = PostgresPersistenceSupport.parseId(taskId);
        RequirementPublicationRow row = publicationMapper.selectByOperationIdForUpdate(receipt.operationId());
        if (row == null
                || !receipt.operationId().equals(safe(row.operationId))
                || row.taskId == null || row.taskId != expectedTaskId
                || !receipt.durableState().equalsIgnoreCase(safe(row.status))) {
            throw new IllegalStateException("publication receipt does not match the locked ledger row: "
                    + receipt.operationId());
        }
        if (!"PR_CONFIRMED".equalsIgnoreCase(row.status) && !"COMMITTED".equalsIgnoreCase(row.status)) {
            throw new IllegalStateException("publication receipt is not finalizable: " + receipt.operationId());
        }
        requireReceiptIdentity(receipt, taskId, plan, row);
        return row;
    }

    private void commitPublicationReceipt(RequirementPublicationRow row, long nowEpochMillis) {
        if (row == null || "COMMITTED".equalsIgnoreCase(row.status)) {
            return;
        }
        if (publicationMapper.commitConfirmedReceipt(
                row.id, row.operationId, row.taskId, row.version,
                PostgresPersistenceSupport.toDateTime(nowEpochMillis)) != 1) {
            throw new IllegalStateException("publication receipt compare-and-set failed: " + row.operationId);
        }
    }

    private static void requireReceiptIdentity(
            ExternalEffectReceipt receipt,
            String taskId,
            RequirementStageExecutionPlan plan,
            RequirementPublicationRow row
    ) {
        try {
            var payload = OBJECT_MAPPER.readTree(receipt.receiptJson());
            if (payload == null || !payload.isObject()) {
                throw new IllegalStateException("publication receipt payload must be an object: " + receipt.operationId());
            }
            if (payload.hasNonNull("operationId")
                    && !receipt.operationId().equals(payload.path("operationId").asText())) {
                throw new IllegalStateException("publication receipt payload operation does not match: "
                        + receipt.operationId());
            }
            if (payload.hasNonNull("taskId") && !taskId.equals(payload.path("taskId").asText())) {
                throw new IllegalStateException("publication receipt payload task does not match: "
                        + receipt.operationId());
            }
            String receiptUrl = payload.path("pullRequestUrl").asText("");
            int receiptNumber = payload.path("pullRequestNumber").asInt(-1);
            String plannedUrl = plan.mutations().isEmpty()
                    ? "" : plan.mutations().getLast().pullRequestUrl();
            if (receiptUrl.isBlank() || receiptNumber < 0
                    || !receiptUrl.equals(safe(row.pullRequestUrl))
                    || row.pullRequestNumber == null || receiptNumber != row.pullRequestNumber
                    || !receiptUrl.equals(plannedUrl)) {
                throw new IllegalStateException("publication receipt pull request does not match: "
                        + receipt.operationId());
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("publication receipt payload cannot be decoded: " + receipt.operationId(),
                    exception);
        }
    }

    private static String encodePlan(RequirementStageExecutionPlan plan) {
        try {
            return OBJECT_MAPPER.writeValueAsString(plan);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stage execution plan cannot be serialized", exception);
        }
    }


    private void applyTaskMutation(FinalizationCommand command, RequirementStageFinalization marker) {
        if (command.taskMutationDisposition() == TaskMutationDisposition.ALREADY_APPLIED
                || command.plan().mutations().isEmpty()
                || defersRetryableMutation(command)) {
            return;
        }
        RdTaskRow current = taskMapper.selectById(PostgresPersistenceSupport.parseId(marker.taskId()));
        if (current == null) {
            throw new IllegalStateException("stage task snapshot is missing: " + marker.taskId());
        }
        long expectedVersion = marker.expectedTaskVersion();
        long expectedFencingToken = marker.expectedFencingToken();
        for (RequirementTaskMutation mutation : command.plan().mutations()) {
            int changed = taskMapper.advanceStatusWithExpectedVersionFenced(
                    PostgresPersistenceSupport.parseId(marker.taskId()),
                    expectedVersion,
                    expectedFencingToken,
                    mutation.fromStatus().name(),
                    mutation.toStatus().name(),
                    mutation.errorMessage(),
                    mutation.executionResultReplacementOrNull(),
                    mutation.pullRequestUrlReplacementOrNull(),
                    mutation.promptSnapshotReplacementOrNull(),
                    PostgresPersistenceSupport.toDateTime(command.nowEpochMillis()));
            if (changed != 1) {
                throw new IllegalStateException("stage task snapshot compare-and-set failed: " + marker.taskId());
            }
            RdTaskStatusEventRow event = new RdTaskStatusEventRow();
            event.id = eventIdGenerator.nextId();
            event.taskId = PostgresPersistenceSupport.parseId(marker.taskId());
            event.status = mutation.toStatus().name();
            event.title = safe(current.title);
            event.message = mutation.eventMessage();
            event.enteredAt = PostgresPersistenceSupport.toDateTime(command.nowEpochMillis());
            event.durationMs = 0L;
            event.trigger = "SYSTEM";
            if (taskStatusEventMapper.insert(event) != 1) {
                throw new IllegalStateException("stage task timeline append failed: " + marker.taskId());
            }
            expectedVersion++;
            expectedFencingToken++;
        }
    }

    private static boolean defersRetryableMutation(FinalizationCommand command) {
        return command.plan().commandDisposition()
                == com.wish.rd.engine.requirement.job.model.CommandDisposition.RETRYABLE_TECHNICAL_FAILURE
                && command.stageCommand().attemptNo() < command.stageCommand().maxAttempts();
    }

    private void applyJobDisposition(FinalizationCommand command) {
        RequirementDeliveryJob job = command.umbrellaJob();
        if (job == null || command.jobDisposition() == JobDisposition.NONE) {
            return;
        }
        var now = PostgresPersistenceSupport.toDateTime(command.nowEpochMillis());
        RequirementDeliveryJobRow updated = switch (command.jobDisposition()) {
            case COMPLETE -> jobMapper.complete(
                    PostgresPersistenceSupport.parseId(job.jobId()), requireOwner(command.leaseOwner()), now);
            case FAIL_RETRYABLE -> jobMapper.fail(
                    PostgresPersistenceSupport.parseId(job.jobId()), requireOwner(command.leaseOwner()),
                    command.outcome().errorMessage(), now);
            case CANCEL -> jobMapper.cancelByTask(
                    PostgresPersistenceSupport.parseId(job.taskId()), command.outcome().errorMessage(), now);
            case NONE -> null;
        };
        if (updated == null && command.jobDisposition() != JobDisposition.CANCEL) {
            throw new IllegalStateException("requirement delivery job finalization lease update failed: "
                    + job.jobId());
        }
    }

    private RequirementStageFinalizationRow toRow(RequirementStageFinalization value) {
        RequirementStageFinalizationRow row = new RequirementStageFinalizationRow();
        row.commandId = PostgresPersistenceSupport.parseId(value.commandId());
        row.attemptNo = value.attemptNo();
        row.taskId = PostgresPersistenceSupport.parseId(value.taskId());
        row.expectedTaskVersion = value.expectedTaskVersion();
        row.expectedFencingToken = value.expectedFencingToken();
        row.expectedTaskStatus = value.expectedTaskStatus().name();
        row.stage = value.stage();
        row.state = value.state().name();
        row.outcomeStatus = value.outcomeStatus() == null ? "" : value.outcomeStatus().name();
        row.resultJson = value.resultJson();
        row.errorMessage = value.errorMessage();
        row.outcomePlanJson = value.outcomePlanJson().isBlank() ? null : value.outcomePlanJson();
        row.outcomePlanDigest = value.outcomePlanDigest();
        row.nextCommandId = value.nextCommandId().isBlank()
                ? null : PostgresPersistenceSupport.parseId(value.nextCommandId());
        row.preparedAt = PostgresPersistenceSupport.toDateTime(value.preparedAtEpochMillis());
        row.finalizedAt = value.finalizedAtEpochMillis() <= 0L
                ? null : PostgresPersistenceSupport.toDateTime(value.finalizedAtEpochMillis());
        return row;
    }

    private RequirementStageFinalization toFinalization(RequirementStageFinalizationRow row) {
        return new RequirementStageFinalization(
                PostgresPersistenceSupport.idString(row.commandId),
                row.attemptNo == null ? 0 : row.attemptNo,
                PostgresPersistenceSupport.idString(row.taskId),
                row.expectedTaskVersion == null ? 0L : row.expectedTaskVersion,
                row.expectedFencingToken == null ? 0L : row.expectedFencingToken,
                parseExpectedTaskStatus(row.expectedTaskStatus),
                safe(row.stage),
                parseState(row.state),
                parseOutcomeStatus(row.outcomeStatus),
                safe(row.resultJson),
                safe(row.errorMessage),
                safe(row.outcomePlanJson),
                safe(row.outcomePlanDigest),
                row.nextCommandId == null ? "" : PostgresPersistenceSupport.idString(row.nextCommandId),
                PostgresPersistenceSupport.toEpochMillis(row.preparedAt),
                PostgresPersistenceSupport.toEpochMillis(row.finalizedAt)
        );
    }

    private RequirementStageCommandRow toRow(RequirementStageCommand command) {
        if (command == null || command.fencingToken() <= 0L) {
            throw new IllegalArgumentException("stage command fencingToken must be positive before persistence");
        }
        RequirementStageCommandRow row = new RequirementStageCommandRow();
        row.id = PostgresPersistenceSupport.parseId(command.commandId());
        row.taskId = PostgresPersistenceSupport.parseId(command.taskId());
        row.taskVersion = command.taskVersion();
        row.fencingToken = command.fencingToken();
        row.role = command.role();
        row.stage = command.stage();
        row.policyRunId = command.policyRunId().isBlank()
                ? null : PostgresPersistenceSupport.parseId(command.policyRunId());
        row.retryCheckpointId = command.retryCheckpointId().isBlank()
                ? null : PostgresPersistenceSupport.parseId(command.retryCheckpointId());
        row.businessGeneration = command.businessGeneration();
        row.targetRetryBindingId = command.targetRetryBindingId().isBlank()
                ? null : PostgresPersistenceSupport.parseId(command.targetRetryBindingId());
        row.attemptNo = command.attemptNo();
        row.maxAttempts = command.maxAttempts();
        row.deadlineAt = command.deadlineEpochMillis() <= 0L
                ? null : PostgresPersistenceSupport.toDateTime(command.deadlineEpochMillis());
        row.resourceClass = command.resourceClass().name();
        row.resourceRequirements = command.encodedResourceRequirements();
        row.projectId = command.projectId();
        row.providerId = command.providerId();
        row.priorityRank = command.priorityRank();
        row.status = command.status().name();
        row.leaseOwner = command.leaseOwner();
        row.leaseUntil = command.leaseUntilEpochMillis() <= 0L
                ? null : PostgresPersistenceSupport.toDateTime(command.leaseUntilEpochMillis());
        row.nextVisibleAt = PostgresPersistenceSupport.toDateTime(command.nextVisibleAtEpochMillis());
        row.lastError = command.lastError();
        row.createdAt = PostgresPersistenceSupport.toDateTime(command.createdAtEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(command.updatedAtEpochMillis());
        return row;
    }

    private RequirementStageCommand toCommand(RequirementStageCommandRow row) {
        ScheduleResourceClass resource = parseResource(row.resourceClass);
        return new RequirementStageCommand(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.taskId),
                row.taskVersion == null ? 0L : row.taskVersion,
                row.fencingToken == null ? 0L : row.fencingToken,
                safe(row.role),
                safe(row.stage),
                row.attemptNo == null ? 0 : row.attemptNo,
                row.maxAttempts == null ? 1 : row.maxAttempts,
                PostgresPersistenceSupport.toEpochMillis(row.deadlineAt),
                resource,
                RequirementStageCommand.decodeResourceRequirements(row.resourceRequirements, resource),
                safe(row.projectId),
                safe(row.providerId),
                row.priorityRank == null ? 2 : row.priorityRank,
                RequirementStageCommand.Status.valueOf(safe(row.status)),
                safe(row.leaseOwner),
                PostgresPersistenceSupport.toEpochMillis(row.leaseUntil),
                PostgresPersistenceSupport.toEpochMillis(row.nextVisibleAt),
                safe(row.lastError),
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt),
                row.policyRunId == null ? "" : PostgresPersistenceSupport.idString(row.policyRunId),
                row.retryCheckpointId == null ? "" : PostgresPersistenceSupport.idString(row.retryCheckpointId),
                row.businessGeneration == null ? 0L : row.businessGeneration,
                row.targetRetryBindingId == null ? "" : PostgresPersistenceSupport.idString(row.targetRetryBindingId)
        );
    }

    private static RequirementStageFinalization.State parseState(String value) {
        try {
            return RequirementStageFinalization.State.valueOf(safe(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("unknown stage finalization state: " + safe(value), exception);
        }
    }

    private static RdTaskStatus parseOutcomeStatus(String value) {
        if (safe(value).isBlank()) {
            return null;
        }
        try {
            return RdTaskStatus.valueOf(safe(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("unknown stage finalization outcome: " + safe(value), exception);
        }
    }

    private static RdTaskStatus parseExpectedTaskStatus(String value) {
        try {
            return RdTaskStatus.valueOf(safe(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("unknown stage finalization expected status: " + safe(value), exception);
        }
    }

    private static ScheduleResourceClass parseResource(String value) {
        try {
            return ScheduleResourceClass.valueOf(safe(value));
        } catch (IllegalArgumentException exception) {
            return ScheduleResourceClass.GENERIC;
        }
    }

    private static void requireCommand(RequirementStageCommand command) {
        if (command == null || command.fencingToken() <= 0L) {
            throw new IllegalArgumentException("stage finalization requires a positive command fencingToken");
        }
    }

    private static String requireOwner(String value) {
        String owner = safe(value);
        if (owner.isBlank()) {
            throw new IllegalArgumentException("leaseOwner must not be blank");
        }
        return owner;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
