package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.RequirementStageCommandRow;
import com.wish.rd.bootstrap.persistence.mapper.RequirementStageCommandMapper;
import com.wish.rd.engine.requirement.job.RequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.FairRequirementDeliveryClaimPlanner;
import com.wish.rd.engine.scheduling.model.RequirementDeliverySchedulingPolicy;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** PostgreSQL adapter for persistent stage command claims and leases. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresRequirementStageCommandStore implements RequirementStageCommandStore {

    /** Stable PostgreSQL advisory-lock key for cross-instance fair stage admission. */
    private static final long FAIR_ADMISSION_LOCK_KEY = 7_842_310_719_431L;

    private final RequirementStageCommandMapper mapper;
    private final FairRequirementDeliveryClaimPlanner claimPlanner = new FairRequirementDeliveryClaimPlanner();

    public PostgresRequirementStageCommandStore(RequirementStageCommandMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public RequirementStageCommand enqueue(RequirementStageCommand command) {
        mapper.enqueue(toRow(command));
        RequirementStageCommand effective = find(command.taskId(), command.role(), command.stage(), command.retryCheckpointId())
                .orElseThrow(() -> new IllegalStateException("stage command enqueue did not return an effective row"));
        requireExactEnqueueIdentity(command, effective);
        return effective;
    }

    @Override
    public Optional<RequirementStageCommand> find(String taskId, String role, String stage) {
        RequirementStageCommandRow row = mapper.find(
                PostgresPersistenceSupport.parseId(taskId), role == null ? "" : role,
                stage == null ? "" : stage);
        return Optional.ofNullable(row).map(this::toCommand);
    }

    @Override
    public Optional<RequirementStageCommand> find(
            String taskId, String role, String stage, String retryCheckpointId
    ) {
        RequirementStageCommandRow row = mapper.findByIdentity(
                PostgresPersistenceSupport.parseId(taskId), role == null ? "" : role,
                stage == null ? "" : stage, retryCheckpointId == null ? "" : retryCheckpointId);
        return Optional.ofNullable(row).map(this::toCommand);
    }

    @Override
    public Optional<RequirementStageCommand> findById(String commandId) {
        RequirementStageCommandRow row = mapper.findById(PostgresPersistenceSupport.parseId(commandId));
        return Optional.ofNullable(row).map(this::toCommand);
    }

    @Override
    public List<RequirementStageCommand> claimBatch(
            String leaseOwner,
            long nowEpochMillis,
            long leaseMillis,
            int batchSize
    ) {
        return mapper.claimBatch(
                        requireOwner(leaseOwner),
                        PostgresPersistenceSupport.toDateTime(nowEpochMillis),
                        PostgresPersistenceSupport.toDateTime(nowEpochMillis + Math.max(1L, leaseMillis)),
                        Math.max(1, batchSize)
                ).stream()
                .map(this::toCommand)
                .toList();
    }

    @Override
    public Optional<RequirementStageCommand> claim(
            String commandId,
            String leaseOwner,
            long nowEpochMillis,
            long leaseMillis
    ) {
        RequirementStageCommandRow row = mapper.claimOne(
                PostgresPersistenceSupport.parseId(commandId), requireOwner(leaseOwner),
                PostgresPersistenceSupport.toDateTime(nowEpochMillis),
                PostgresPersistenceSupport.toDateTime(nowEpochMillis + Math.max(1L, leaseMillis))
        );
        return Optional.ofNullable(row).map(this::toCommand);
    }

    @Override
    public List<RequirementStageCommand> claimBatchById(
            List<String> commandIds,
            String leaseOwner,
            long nowEpochMillis,
            long leaseMillis,
            int batchSize
    ) {
        if (commandIds == null || commandIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = commandIds.stream()
                .map(PostgresPersistenceSupport::parseId)
                .toList();
        return mapper.claimSelectedBatch(
                        ids,
                        requireOwner(leaseOwner),
                        PostgresPersistenceSupport.toDateTime(nowEpochMillis),
                        PostgresPersistenceSupport.toDateTime(nowEpochMillis + Math.max(1L, leaseMillis)),
                        Math.max(1, batchSize))
                .stream()
                .map(this::toCommand)
                .toList();
    }

    /**
     * Re-checks the current candidate and active-lease snapshots while holding one PostgreSQL
     * transaction-scoped advisory lock. The Host snapshot is therefore advisory only: stale
     * candidates are discarded and the planner sees the authoritative resource/provider loads
     * immediately before the selected rows are leased.
     */
    @Override
    @Transactional
    public List<RequirementStageCommand> claimFairBatch(
            List<String> commandIds,
            String leaseOwner,
            long nowEpochMillis,
            long leaseMillis,
            RequirementDeliverySchedulingPolicy policy
    ) {
        if (commandIds == null || commandIds.isEmpty()) {
            return List.of();
        }
        RequirementDeliverySchedulingPolicy safePolicy = policy == null
                ? RequirementDeliverySchedulingPolicy.defaults() : policy;
        String owner = requireOwner(leaseOwner);
        var now = PostgresPersistenceSupport.toDateTime(nowEpochMillis);
        mapper.acquireFairAdmissionLock(FAIR_ADMISSION_LOCK_KEY);

        List<Long> ids = commandIds.stream()
                .map(PostgresPersistenceSupport::parseId)
                .distinct()
                .toList();
        List<RequirementStageCommand> candidates = mapper.lockFairCandidates(ids, now).stream()
                .map(this::toCommand)
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<RequirementStageCommand> inFlight = mapper.inFlightForFairAdmission(now).stream()
                .map(this::toCommand)
                .toList();
        List<RequirementStageCommand> admitted = claimPlanner.planStageCommands(
                candidates,
                inFlight,
                safePolicy.limits(),
                nowEpochMillis,
                safePolicy.projectWeights()
        );
        if (admitted.isEmpty()) {
            return List.of();
        }
        return mapper.claimSelectedBatch(
                        admitted.stream()
                                .map(RequirementStageCommand::commandId)
                                .map(PostgresPersistenceSupport::parseId)
                                .toList(),
                        owner,
                        now,
                        PostgresPersistenceSupport.toDateTime(nowEpochMillis + Math.max(1L, leaseMillis)),
                        safePolicy.limits().batchSize())
                .stream()
                .map(this::toCommand)
                .toList();
    }

    @Override
    public RequirementStageCommand heartbeat(
            String commandId,
            int expectedAttemptNo,
            String leaseOwner,
            long nowEpochMillis,
            long leaseMillis
    ) {
        return required(mapper.heartbeatAttempt(
                PostgresPersistenceSupport.parseId(commandId), expectedAttemptNo, requireOwner(leaseOwner),
                PostgresPersistenceSupport.toDateTime(nowEpochMillis),
                PostgresPersistenceSupport.toDateTime(nowEpochMillis + Math.max(1L, leaseMillis))
        ), commandId);
    }

    @Override
    public RequirementStageCommand complete(
            String commandId, int expectedAttemptNo, String leaseOwner, long nowEpochMillis
    ) {
        return required(mapper.completeAttempt(
                PostgresPersistenceSupport.parseId(commandId), expectedAttemptNo, requireOwner(leaseOwner),
                PostgresPersistenceSupport.toDateTime(nowEpochMillis)
        ), commandId);
    }

    @Override
    public RequirementStageCommand fail(
            String commandId,
            int expectedAttemptNo,
            String leaseOwner,
            String errorMessage,
            long nowEpochMillis
    ) {
        return required(mapper.failAttempt(
                PostgresPersistenceSupport.parseId(commandId), expectedAttemptNo, requireOwner(leaseOwner),
                errorMessage == null ? "" : errorMessage,
                PostgresPersistenceSupport.toDateTime(nowEpochMillis),
                PostgresPersistenceSupport.toDateTime(nowEpochMillis)
        ), commandId);
    }

    @Override
    public List<RequirementStageCommand> recoverable(long nowEpochMillis, int limit) {
        return recoverable(nowEpochMillis, limit, RequirementDeliverySchedulingPolicy.defaults());
    }

    @Override
    public List<RequirementStageCommand> recoverable(
            long nowEpochMillis,
            int limit,
            RequirementDeliverySchedulingPolicy policy
    ) {
        RequirementDeliverySchedulingPolicy safePolicy = policy == null
                ? RequirementDeliverySchedulingPolicy.defaults() : policy;
        return mapper.recoverable(
                        PostgresPersistenceSupport.toDateTime(nowEpochMillis),
                        Math.max(1, limit),
                        safePolicy.limits().agingMillis())
                .stream().map(this::toCommand).toList();
    }

    @Override
    public List<RequirementStageCommand> inFlight(long nowEpochMillis, int limit) {
        return mapper.inFlight(
                        PostgresPersistenceSupport.toDateTime(nowEpochMillis), Math.max(1, limit))
                .stream().map(this::toCommand).toList();
    }

    @Override
    public List<RequirementStageCommand> deadLetterExpired(long nowEpochMillis, int limit) {
        return mapper.deadLetterExpired(
                        PostgresPersistenceSupport.toDateTime(nowEpochMillis), Math.max(1, limit))
                .stream()
                .map(this::toCommand)
                .toList();
    }

    private RequirementStageCommandRow toRow(RequirementStageCommand command) {
        if (command == null || command.fencingToken() <= 0L) {
            throw new IllegalArgumentException("new stage command fencingToken must be positive");
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
        ScheduleResourceClass resourceClass;
        try {
            resourceClass = ScheduleResourceClass.valueOf(row.resourceClass);
        } catch (RuntimeException exception) {
            resourceClass = ScheduleResourceClass.GENERIC;
        }
        RequirementStageCommand.Status status = RequirementStageCommand.Status.valueOf(row.status);
        return new RequirementStageCommand(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.taskId),
                row.taskVersion == null ? 0L : row.taskVersion,
                row.fencingToken == null ? 0L : row.fencingToken,
                row.role,
                row.stage,
                row.attemptNo == null ? 0 : row.attemptNo,
                row.maxAttempts == null ? 3 : row.maxAttempts,
                row.deadlineAt == null ? 0L : PostgresPersistenceSupport.toEpochMillis(row.deadlineAt),
                resourceClass,
                RequirementStageCommand.decodeResourceRequirements(row.resourceRequirements, resourceClass),
                row.projectId,
                row.providerId,
                row.priorityRank == null ? 2 : row.priorityRank,
                status,
                row.leaseOwner,
                row.leaseUntil == null ? 0L : PostgresPersistenceSupport.toEpochMillis(row.leaseUntil),
                row.nextVisibleAt == null ? 0L : PostgresPersistenceSupport.toEpochMillis(row.nextVisibleAt),
                row.lastError,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt),
                row.policyRunId == null ? "" : PostgresPersistenceSupport.idString(row.policyRunId),
                row.retryCheckpointId == null ? "" : PostgresPersistenceSupport.idString(row.retryCheckpointId),
                row.businessGeneration == null ? 0L : row.businessGeneration,
                row.targetRetryBindingId == null ? "" : PostgresPersistenceSupport.idString(row.targetRetryBindingId)
        );
    }

    private RequirementStageCommand required(RequirementStageCommandRow row, String commandId) {
        if (row == null) {
            throw new IllegalStateException("stage command lease update failed: " + commandId);
        }
        return toCommand(row);
    }

    private String requireOwner(String owner) {
        String normalized = owner == null ? "" : owner.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("leaseOwner must not be blank");
        }
        return normalized;
    }

    private static void requireExactEnqueueIdentity(
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
                || !requested.policyRunId().equals(effective.policyRunId())
                || !requested.retryCheckpointId().equals(effective.retryCheckpointId())
                || requested.businessGeneration() != effective.businessGeneration()
                || !requested.targetRetryBindingId().equals(effective.targetRetryBindingId())) {
            throw new IllegalStateException("stage command enqueue conflict has a different durable identity");
        }
    }
}
