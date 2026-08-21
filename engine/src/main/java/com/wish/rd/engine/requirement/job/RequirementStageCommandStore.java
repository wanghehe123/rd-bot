package com.wish.rd.engine.requirement.job;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.model.RequirementDeliverySchedulingPolicy;

import java.util.List;
import java.util.Optional;

/** Persistence port for bounded, lease-based requirement stage commands. */
public interface RequirementStageCommandStore {

    RequirementStageCommand enqueue(RequirementStageCommand command);

    /** Returns the durable command for one task/role/stage identity, when present. */
    default Optional<RequirementStageCommand> find(String taskId, String role, String stage) {
        return Optional.empty();
    }

    /** Returns one command under its normal or checkpoint-bound durable identity. */
    default Optional<RequirementStageCommand> find(
            String taskId, String role, String stage, String retryCheckpointId
    ) {
        if (retryCheckpointId != null && !retryCheckpointId.isBlank()) {
            return Optional.empty();
        }
        return find(taskId, role, stage);
    }

    /** Returns one command under its remediation-round generation identity. */
    default Optional<RequirementStageCommand> findByRemediation(
            String taskId, String role, String stage, String remediationRoundId
    ) {
        return Optional.empty();
    }

    /** Returns the durable command for one command identity, when present. */
    Optional<RequirementStageCommand> findById(String commandId);

    List<RequirementStageCommand> claimBatch(
            String leaseOwner,
            long nowEpochMillis,
            long leaseMillis,
            int batchSize
    );

    /**
     * Re-admits a Host-planned candidate set against the current durable leases and claims only
     * commands that still fit the configured project, resource, and provider quotas. Production
     * adapters must make this one transactional admission decision rather than treating the
     * planner snapshot and row lease as separate cross-instance operations.
     *
     * @param commandIds Host-planned candidate ids
     * @param leaseOwner scheduler worker that will own the lease
     * @param nowEpochMillis scheduler clock
     * @param leaseMillis lease duration
     * @param policy configured fair-scheduling policy
     * @return commands actually admitted and leased, never {@code null}
     */
    List<RequirementStageCommand> claimFairBatch(
            List<String> commandIds,
            String leaseOwner,
            long nowEpochMillis,
            long leaseMillis,
            RequirementDeliverySchedulingPolicy policy
    );

    /**
     * Atomically claims the fair-plan command ids under the same lease predicate as batch claims.
     * PostgreSQL implementations use {@code FOR UPDATE SKIP LOCKED}; memory implementations keep
     * the operation synchronized. The returned list contains only ids that were actually leased.
     */
    default List<RequirementStageCommand> claimBatchById(
            List<String> commandIds,
            String leaseOwner,
            long nowEpochMillis,
            long leaseMillis,
            int batchSize
    ) {
        if (commandIds == null || commandIds.isEmpty()) {
            return List.of();
        }
        List<RequirementStageCommand> claimed = new java.util.ArrayList<>();
        for (String commandId : commandIds.stream().limit(Math.max(1, batchSize)).toList()) {
            claim(commandId, leaseOwner, nowEpochMillis, leaseMillis).ifPresent(claimed::add);
        }
        return List.copyOf(claimed);
    }

    /** Returns and dead-letters commands whose explicit deadline has elapsed. */
    default List<RequirementStageCommand> deadLetterExpired(long nowEpochMillis, int limit) {
        return List.of();
    }

    /** Claims one known command while retaining the same lease/attempt predicates as batch claims. */
    Optional<RequirementStageCommand> claim(
            String commandId,
            String leaseOwner,
            long nowEpochMillis,
            long leaseMillis
    );

    default RequirementStageCommand heartbeat(
            String commandId,
            int expectedAttemptNo,
            String leaseOwner,
            long nowEpochMillis,
            long leaseMillis
    ) {
        return heartbeat(commandId, leaseOwner, nowEpochMillis, leaseMillis);
    }

    default RequirementStageCommand complete(
            String commandId,
            int expectedAttemptNo,
            String leaseOwner,
            long nowEpochMillis
    ) {
        return complete(commandId, leaseOwner, nowEpochMillis);
    }

    default RequirementStageCommand fail(
            String commandId,
            int expectedAttemptNo,
            String leaseOwner,
            String errorMessage,
            long nowEpochMillis
    ) {
        return fail(commandId, leaseOwner, errorMessage, nowEpochMillis);
    }

    /** Applies the lease CAS using the immutable attempt held by the worker. */
    default RequirementStageCommand heartbeat(
            RequirementStageCommand command, String leaseOwner, long nowEpochMillis, long leaseMillis
    ) {
        return heartbeat(command.commandId(), command.attemptNo(), leaseOwner, nowEpochMillis, leaseMillis);
    }

    /** Applies the completion CAS using the immutable attempt held by the worker. */
    default RequirementStageCommand complete(
            RequirementStageCommand command, String leaseOwner, long nowEpochMillis
    ) {
        return complete(command.commandId(), command.attemptNo(), leaseOwner, nowEpochMillis);
    }

    /** Applies the retry CAS using the immutable attempt held by the worker. */
    default RequirementStageCommand fail(
            RequirementStageCommand command, String leaseOwner, String errorMessage, long nowEpochMillis
    ) {
        return fail(command.commandId(), command.attemptNo(), leaseOwner, errorMessage, nowEpochMillis);
    }

    /** Compatibility access for callers that have not retained the lease attempt. */
    @Deprecated(forRemoval = false)
    default RequirementStageCommand heartbeat(
            String commandId, String leaseOwner, long nowEpochMillis, long leaseMillis
    ) {
        RequirementStageCommand command = findById(commandId)
                .orElseThrow(() -> new IllegalStateException("stage command not found: " + commandId));
        return heartbeat(command, leaseOwner, nowEpochMillis, leaseMillis);
    }

    /** Compatibility access for callers that have not retained the lease attempt. */
    @Deprecated(forRemoval = false)
    default RequirementStageCommand complete(String commandId, String leaseOwner, long nowEpochMillis) {
        RequirementStageCommand command = findById(commandId)
                .orElseThrow(() -> new IllegalStateException("stage command not found: " + commandId));
        return complete(command, leaseOwner, nowEpochMillis);
    }

    /** Compatibility access for callers that have not retained the lease attempt. */
    @Deprecated(forRemoval = false)
    default RequirementStageCommand fail(
            String commandId, String leaseOwner, String errorMessage, long nowEpochMillis
    ) {
        RequirementStageCommand command = findById(commandId)
                .orElseThrow(() -> new IllegalStateException("stage command not found: " + commandId));
        return fail(command, leaseOwner, errorMessage, nowEpochMillis);
    }

    List<RequirementStageCommand> recoverable(long nowEpochMillis, int limit);

    /**
     * Returns a bounded recovery candidate window using the same aging interval as fair claim
     * admission. Implementations may retain the two-argument method for compatibility callers.
     *
     * @param nowEpochMillis scheduler clock
     * @param limit maximum candidate rows to return
     * @param policy scheduling policy that owns the aging interval
     * @return recovery candidates, never {@code null}
     */
    default List<RequirementStageCommand> recoverable(
            long nowEpochMillis,
            int limit,
            RequirementDeliverySchedulingPolicy policy
    ) {
        return recoverable(nowEpochMillis, limit);
    }

    /**
     * Returns non-expired running commands used for project/resource quota accounting.
     * Implementations that cannot provide this projection may return an empty snapshot; claims
     * remain protected by their atomic lease predicate.
     *
     * @param nowEpochMillis current scheduler time
     * @param limit maximum rows to inspect
     * @return running command snapshot, never {@code null}
     */
    default List<RequirementStageCommand> inFlight(long nowEpochMillis, int limit) {
        return List.of();
    }

    default List<RequirementStageCommand> recoverable(long nowEpochMillis) {
        return recoverable(nowEpochMillis, 64);
    }
}
