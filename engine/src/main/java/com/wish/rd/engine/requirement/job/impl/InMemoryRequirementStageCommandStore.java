package com.wish.rd.engine.requirement.job.impl;

import com.wish.rd.engine.requirement.job.RequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.FairRequirementDeliveryClaimPlanner;
import com.wish.rd.engine.scheduling.model.RequirementDeliverySchedulingPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/** In-memory stage command store for unit tests and explicit memory mode. */
public final class InMemoryRequirementStageCommandStore implements RequirementStageCommandStore {

    private static final long DEFAULT_AGING_MILLIS = RequirementDeliverySchedulingPolicy.defaults()
            .limits().agingMillis();

    private final LinkedHashMap<String, RequirementStageCommand> commands = new LinkedHashMap<>();
    private final FairRequirementDeliveryClaimPlanner claimPlanner = new FairRequirementDeliveryClaimPlanner();

    @Override
    public synchronized RequirementStageCommand enqueue(RequirementStageCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (command.fencingToken() <= 0L) {
            throw new IllegalArgumentException("new stage command fencingToken must be positive");
        }
        RequirementStageCommand existingIdentity = commands.values().stream()
                .filter(existing -> existing.taskId().equals(command.taskId()))
                .filter(existing -> existing.role().equals(command.role()))
                .filter(existing -> existing.stage().equals(command.stage()))
                .filter(existing -> existing.retryCheckpointId().equals(command.retryCheckpointId()))
                .filter(existing -> existing.remediationRoundId().equals(command.remediationRoundId()))
                .findFirst()
                .orElse(null);
        if (existingIdentity != null) {
            requireExactEnqueueIdentity(command, existingIdentity);
            return existingIdentity;
        }
        RequirementStageCommand existing = commands.putIfAbsent(command.commandId(), command);
        RequirementStageCommand effective = existing == null ? command : existing;
        requireExactEnqueueIdentity(command, effective);
        return effective;
    }

    @Override
    public synchronized Optional<RequirementStageCommand> find(String taskId, String role, String stage) {
        String expectedTask = safe(taskId);
        String expectedRole = safe(role);
        String expectedStage = safe(stage);
        return commands.values().stream()
                .filter(command -> command.taskId().equals(expectedTask))
                .filter(command -> command.role().equals(expectedRole))
                .filter(command -> command.stage().equals(expectedStage))
                .filter(command -> command.retryCheckpointId().isBlank())
                .filter(command -> command.remediationRoundId().isBlank())
                .findFirst();
    }

    @Override
    public synchronized Optional<RequirementStageCommand> find(
            String taskId, String role, String stage, String retryCheckpointId
    ) {
        String expectedTask = safe(taskId);
        String expectedRole = safe(role);
        String expectedStage = safe(stage);
        String expectedCheckpoint = safe(retryCheckpointId);
        return commands.values().stream()
                .filter(command -> command.taskId().equals(expectedTask))
                .filter(command -> command.role().equals(expectedRole))
                .filter(command -> command.stage().equals(expectedStage))
                .filter(command -> command.retryCheckpointId().equals(expectedCheckpoint))
                .filter(command -> command.remediationRoundId().isBlank())
                .findFirst();
    }

    @Override
    public synchronized Optional<RequirementStageCommand> findByRemediation(
            String taskId, String role, String stage, String remediationRoundId
    ) {
        String expectedRound = safe(remediationRoundId);
        return commands.values().stream()
                .filter(command -> command.taskId().equals(safe(taskId)))
                .filter(command -> command.role().equals(safe(role)))
                .filter(command -> command.stage().equals(safe(stage)))
                .filter(command -> command.retryCheckpointId().isBlank())
                .filter(command -> command.remediationRoundId().equals(expectedRound))
                .findFirst();
    }

    @Override
    public synchronized Optional<RequirementStageCommand> findById(String commandId) {
        return Optional.ofNullable(commands.get(safe(commandId)));
    }

    @Override
    public synchronized Optional<RequirementStageCommand> findLatestAnyGeneration(
            String taskId, String role, String stage
    ) {
        String expectedTask = safe(taskId);
        String expectedRole = safe(role);
        String expectedStage = safe(stage);
        return commands.values().stream()
                .filter(command -> command.taskId().equals(expectedTask))
                .filter(command -> command.role().equals(expectedRole))
                .filter(command -> command.stage().equals(expectedStage))
                .max(Comparator.comparingLong(RequirementStageCommand::createdAtEpochMillis)
                        .thenComparing(RequirementStageCommand::commandId));
    }

    @Override
    public synchronized List<RequirementStageCommand> claimBatch(
            String leaseOwner,
            long nowEpochMillis,
            long leaseMillis,
            int batchSize
    ) {
        String owner = requireOwner(leaseOwner);
        int limit = Math.max(1, batchSize);
        long leaseUntil = nowEpochMillis + Math.max(1L, leaseMillis);
        List<RequirementStageCommand> ranked = commands.values().stream()
                .filter(command -> command.claimable(nowEpochMillis))
                .sorted(Comparator
                        .comparingDouble((RequirementStageCommand command)
                                -> effectivePriority(command, nowEpochMillis))
                        .thenComparingLong(RequirementStageCommand::createdAtEpochMillis)
                        .thenComparing(RequirementStageCommand::commandId))
                .limit(limit)
                .toList();
        List<RequirementStageCommand> claimed = new ArrayList<>(ranked.size());
        for (RequirementStageCommand command : ranked) {
            RequirementStageCommand next = command.claimed(owner, leaseUntil, nowEpochMillis);
            commands.put(command.commandId(), next);
            claimed.add(next);
        }
        return List.copyOf(claimed);
    }

    @Override
    public synchronized Optional<RequirementStageCommand> claim(
            String commandId,
            String leaseOwner,
            long nowEpochMillis,
            long leaseMillis
    ) {
        RequirementStageCommand current = commands.get(safe(commandId));
        if (current == null || !current.claimable(nowEpochMillis)) {
            return Optional.empty();
        }
        RequirementStageCommand claimed = current.claimed(
                requireOwner(leaseOwner), nowEpochMillis + Math.max(1L, leaseMillis), nowEpochMillis);
        commands.put(claimed.commandId(), claimed);
        return Optional.of(claimed);
    }

    @Override
    public synchronized List<RequirementStageCommand> claimBatchById(
            List<String> commandIds,
            String leaseOwner,
            long nowEpochMillis,
            long leaseMillis,
            int batchSize
    ) {
        if (commandIds == null || commandIds.isEmpty()) {
            return List.of();
        }
        String owner = requireOwner(leaseOwner);
        long leaseUntil = nowEpochMillis + Math.max(1L, leaseMillis);
        List<RequirementStageCommand> claimed = new ArrayList<>();
        for (String commandId : commandIds.stream().limit(Math.max(1, batchSize)).toList()) {
            RequirementStageCommand current = commands.get(safe(commandId));
            if (current == null || !current.claimable(nowEpochMillis)) {
                continue;
            }
            RequirementStageCommand next = current.claimed(owner, leaseUntil, nowEpochMillis);
            commands.put(next.commandId(), next);
            claimed.add(next);
        }
        return List.copyOf(claimed);
    }

    @Override
    public synchronized List<RequirementStageCommand> claimFairBatch(
            List<String> commandIds,
            String leaseOwner,
            long nowEpochMillis,
            long leaseMillis,
            RequirementDeliverySchedulingPolicy policy
    ) {
        if (commandIds == null || commandIds.isEmpty()) {
            return List.of();
        }
        String owner = requireOwner(leaseOwner);
        RequirementDeliverySchedulingPolicy safePolicy = policy == null
                ? RequirementDeliverySchedulingPolicy.defaults() : policy;
        List<RequirementStageCommand> candidates = commandIds.stream()
                .map(this::safe)
                .distinct()
                .map(commands::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        List<RequirementStageCommand> active = commands.values().stream()
                .filter(command -> command.status() == RequirementStageCommand.Status.RUNNING)
                .filter(command -> command.leaseUntilEpochMillis() > nowEpochMillis)
                .toList();
        List<RequirementStageCommand> admitted = claimPlanner.planStageCommands(
                candidates,
                active,
                safePolicy.limits(),
                nowEpochMillis,
                safePolicy.projectWeights()
        );
        long leaseUntil = nowEpochMillis + Math.max(1L, leaseMillis);
        List<RequirementStageCommand> claimed = new ArrayList<>(admitted.size());
        for (RequirementStageCommand candidate : admitted) {
            RequirementStageCommand current = commands.get(candidate.commandId());
            if (current == null || !current.claimable(nowEpochMillis)) {
                continue;
            }
            RequirementStageCommand next = current.claimed(owner, leaseUntil, nowEpochMillis);
            commands.put(next.commandId(), next);
            claimed.add(next);
        }
        return List.copyOf(claimed);
    }

    @Override
    public synchronized RequirementStageCommand heartbeat(
            String commandId,
            int expectedAttemptNo,
            String leaseOwner,
            long nowEpochMillis,
            long leaseMillis
    ) {
        RequirementStageCommand current = ownedRunning(commandId, expectedAttemptNo, leaseOwner, nowEpochMillis);
        RequirementStageCommand next = current.heartbeat(
                nowEpochMillis + Math.max(1L, leaseMillis), nowEpochMillis);
        commands.put(next.commandId(), next);
        return next;
    }

    @Override
    public synchronized RequirementStageCommand complete(
            String commandId, int expectedAttemptNo, String leaseOwner, long nowEpochMillis
    ) {
        RequirementStageCommand next = ownedRunning(commandId, expectedAttemptNo, leaseOwner, nowEpochMillis)
                .succeeded(nowEpochMillis);
        commands.put(next.commandId(), next);
        return next;
    }

    /**
     * Atomically completes the leased command and persists an exact continuation in memory mode.
     *
     * <p>The preflight runs before either map entry changes, so an identity conflict cannot leave
     * the current command terminal without its required continuation.
     */
    public synchronized CompletionAndContinuation completeAndEnqueue(
            RequirementStageCommand command,
            String leaseOwner,
            long nowEpochMillis,
            RequirementStageCommand continuation
    ) {
        RequirementStageCommand current = requireCompletionAndContinuationPreflight(
                command, leaseOwner, nowEpochMillis, continuation);
        RequirementStageCommand next = current.succeeded(nowEpochMillis);
        RequirementStageCommand effectiveContinuation = effectiveContinuation(continuation);
        commands.put(next.commandId(), next);
        if (!commands.containsKey(effectiveContinuation.commandId())) {
            commands.put(effectiveContinuation.commandId(), effectiveContinuation);
        }
        return new CompletionAndContinuation(next, effectiveContinuation);
    }

    /** Validates the all-or-nothing memory completion without changing command state. */
    public synchronized void preflightCompletionAndEnqueue(
            RequirementStageCommand command,
            String leaseOwner,
            long nowEpochMillis,
            RequirementStageCommand continuation
    ) {
        requireCompletionAndContinuationPreflight(command, leaseOwner, nowEpochMillis, continuation);
    }

    private RequirementStageCommand requireCompletionAndContinuationPreflight(
            RequirementStageCommand command,
            String leaseOwner,
            long nowEpochMillis,
            RequirementStageCommand continuation
    ) {
        if (continuation == null) {
            throw new IllegalArgumentException("continuation must not be null");
        }
        RequirementStageCommand current = ownedRunning(
                command.commandId(), command.attemptNo(), leaseOwner, nowEpochMillis);
        effectiveContinuation(continuation);
        return current;
    }

    private RequirementStageCommand effectiveContinuation(RequirementStageCommand continuation) {
        RequirementStageCommand existingIdentity = commands.values().stream()
                .filter(existing -> existing.taskId().equals(continuation.taskId()))
                .filter(existing -> existing.role().equals(continuation.role()))
                .filter(existing -> existing.stage().equals(continuation.stage()))
                .filter(existing -> existing.retryCheckpointId().equals(continuation.retryCheckpointId()))
                .findFirst()
                .orElse(null);
        if (existingIdentity != null) {
            requireExactEnqueueIdentity(continuation, existingIdentity);
            return existingIdentity;
        }
        RequirementStageCommand existing = commands.get(continuation.commandId());
        if (existing != null) {
            requireExactEnqueueIdentity(continuation, existing);
            return existing;
        }
        if (continuation.fencingToken() <= 0L) {
            throw new IllegalArgumentException("new stage command fencingToken must be positive");
        }
        return continuation;
    }

    /** Result of an in-memory all-or-nothing completion and continuation insert. */
    public record CompletionAndContinuation(
            RequirementStageCommand completed,
            RequirementStageCommand continuation
    ) {
    }

    @Override
    public synchronized RequirementStageCommand fail(
            String commandId,
            int expectedAttemptNo,
            String leaseOwner,
            String errorMessage,
            long nowEpochMillis
    ) {
        RequirementStageCommand next = ownedRunning(commandId, expectedAttemptNo, leaseOwner, nowEpochMillis)
                .failed(errorMessage, nowEpochMillis);
        commands.put(next.commandId(), next);
        return next;
    }

    @Override
    public synchronized List<RequirementStageCommand> recoverable(long nowEpochMillis, int limit) {
        return recoverable(nowEpochMillis, limit, RequirementDeliverySchedulingPolicy.defaults());
    }

    @Override
    public synchronized List<RequirementStageCommand> recoverable(
            long nowEpochMillis,
            int limit,
            RequirementDeliverySchedulingPolicy policy
    ) {
        RequirementDeliverySchedulingPolicy safePolicy = policy == null
                ? RequirementDeliverySchedulingPolicy.defaults() : policy;
        long agingMillis = safePolicy.limits().agingMillis();
        List<RequirementStageCommand> ordered = commands.values().stream()
                .filter(command -> command.claimable(nowEpochMillis))
                .sorted(Comparator
                        .comparingDouble((RequirementStageCommand command)
                                -> effectivePriority(command, nowEpochMillis, agingMillis))
                        .thenComparingLong(RequirementStageCommand::createdAtEpochMillis)
                        .thenComparing(RequirementStageCommand::commandId))
                .toList();
        Map<String, Integer> ranksByProject = new HashMap<>();
        Map<String, Integer> projectPositions = projectPositions(ordered);
        int projectCount = projectPositions.size();
        int rotationCursor = rotationCursor(nowEpochMillis, agingMillis, projectCount);
        List<RecoveryCandidate> candidates = new ArrayList<>(ordered.size());
        for (RequirementStageCommand command : ordered) {
            int projectRank = ranksByProject.merge(command.projectId(), 1, Integer::sum);
            candidates.add(new RecoveryCandidate(
                    command, projectRank, projectPositions.get(command.projectId())));
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt(RecoveryCandidate::projectRank)
                        // Advance the project head order with the shared aging clock so a fixed
                        // bounded candidate window cannot permanently hide later projects.
                        .thenComparingInt(candidate -> projectTurn(
                                candidate.projectPosition(), rotationCursor, projectCount))
                        .thenComparingDouble(candidate -> effectivePriority(
                                candidate.command(), nowEpochMillis, agingMillis))
                        .thenComparingLong(candidate -> candidate.command().createdAtEpochMillis())
                        .thenComparing(candidate -> candidate.command().commandId()))
                .limit(Math.max(1, limit))
                .map(RecoveryCandidate::command)
                .toList();
    }

    @Override
    public synchronized List<RequirementStageCommand> deadLetterExpired(long nowEpochMillis, int limit) {
        List<RequirementStageCommand> expired = commands.values().stream()
                .filter(command -> command.status() == RequirementStageCommand.Status.PENDING
                        || command.status() == RequirementStageCommand.Status.FAILED_RETRYABLE
                        || command.status() == RequirementStageCommand.Status.RUNNING)
                .filter(command -> command.deadlineEpochMillis() > 0L
                        && command.deadlineEpochMillis() <= nowEpochMillis)
                .limit(Math.max(1, limit))
                .toList();
        List<RequirementStageCommand> dead = new ArrayList<>(expired.size());
        for (RequirementStageCommand command : expired) {
            RequirementStageCommand next = command.failed("stage command deadline exceeded", nowEpochMillis);
            if (next.status() != RequirementStageCommand.Status.DEAD_LETTERED) {
                next = new RequirementStageCommand(
                        next.commandId(), next.taskId(), next.taskVersion(), next.fencingToken(), next.role(),
                        next.stage(), next.attemptNo(), next.maxAttempts(), next.deadlineEpochMillis(),
                        next.resourceClass(), next.resourceRequirements(), next.projectId(), next.providerId(),
                        next.priorityRank(),
                        RequirementStageCommand.Status.DEAD_LETTERED, "", 0L, next.nextVisibleAtEpochMillis(),
                        next.lastError(), next.createdAtEpochMillis(), next.updatedAtEpochMillis(),
                        next.policyRunId(), next.retryCheckpointId(), next.businessGeneration(),
                        next.targetRetryBindingId(), next.remediationRoundId(), next.remediationKind(),
                        next.remediationNo());
            }
            commands.put(next.commandId(), next);
            dead.add(next);
        }
        return List.copyOf(dead);
    }

    @Override
    public synchronized List<RequirementStageCommand> inFlight(long nowEpochMillis, int limit) {
        return commands.values().stream()
                .filter(command -> command.status() == RequirementStageCommand.Status.RUNNING)
                .filter(command -> command.leaseUntilEpochMillis() > nowEpochMillis)
                .limit(Math.max(1, limit))
                .toList();
    }

    /** Exposes status snapshots for deterministic unit assertions. */
    public synchronized List<RequirementStageCommand> listByStatus(RequirementStageCommand.Status status) {
        return commands.values().stream().filter(command -> command.status() == status).toList();
    }

    /**
     * Restores an exact command snapshot during memory-mode exhaustion rollback.
     *
     * @param snapshot command state observed before the failed atomic unit
     */
    public synchronized void restoreSnapshot(RequirementStageCommand snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("command snapshot must not be null");
        }
        commands.put(snapshot.commandId(), snapshot);
    }

    private RequirementStageCommand ownedRunning(
            String commandId, int expectedAttemptNo, String leaseOwner, long nowEpochMillis
    ) {
        RequirementStageCommand current = commands.get(safe(commandId));
        if (current == null) {
            throw new IllegalArgumentException("stage command not found: " + safe(commandId));
        }
        if (current.status() != RequirementStageCommand.Status.RUNNING
                || current.attemptNo() != expectedAttemptNo
                || current.leaseUntilEpochMillis() <= nowEpochMillis
                || !current.leaseOwner().equals(requireOwner(leaseOwner))) {
            throw new IllegalStateException("stage command lease is not owned: " + current.commandId());
        }
        return current;
    }

    private String requireOwner(String owner) {
        String safeOwner = safe(owner);
        if (safeOwner.isBlank()) {
            throw new IllegalArgumentException("leaseOwner must not be blank");
        }
        return safeOwner;
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
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
                || !requested.targetRetryBindingId().equals(effective.targetRetryBindingId())
                || !requested.remediationRoundId().equals(effective.remediationRoundId())
                || requested.remediationKind() != effective.remediationKind()
                || requested.remediationNo() != effective.remediationNo()) {
            throw new IllegalStateException("stage command enqueue conflict has a different durable identity");
        }
    }

    private double effectivePriority(RequirementStageCommand command, long nowEpochMillis) {
        return effectivePriority(command, nowEpochMillis, DEFAULT_AGING_MILLIS);
    }

    private double effectivePriority(
            RequirementStageCommand command,
            long nowEpochMillis,
            long agingMillis
    ) {
        long age = Math.max(0L, nowEpochMillis - command.createdAtEpochMillis());
        return command.priorityRank() - ((double) age / Math.max(1L, agingMillis));
    }

    private Map<String, Integer> projectPositions(List<RequirementStageCommand> ordered) {
        TreeSet<String> projectIds = new TreeSet<>();
        for (RequirementStageCommand command : ordered) {
            projectIds.add(command.projectId());
        }
        Map<String, Integer> positions = new HashMap<>();
        int position = 0;
        for (String projectId : projectIds) {
            positions.put(projectId, position++);
        }
        return positions;
    }

    private int rotationCursor(long nowEpochMillis, long agingMillis, int projectCount) {
        if (projectCount <= 1) {
            return 0;
        }
        long tick = Math.floorDiv(Math.max(0L, nowEpochMillis), Math.max(1L, agingMillis));
        return (int) Math.floorMod(tick, (long) projectCount);
    }

    private int projectTurn(int projectPosition, int rotationCursor, int projectCount) {
        if (projectCount <= 1) {
            return 0;
        }
        return Math.floorMod(projectPosition - rotationCursor, projectCount);
    }

    private record RecoveryCandidate(
            RequirementStageCommand command,
            int projectRank,
            int projectPosition
    ) {
    }
}
