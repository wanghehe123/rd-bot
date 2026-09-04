package com.wish.rd.rag.project.memory.impl;

import com.wish.rd.rag.project.memory.ProjectMemoryOperationStore;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperation;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperationClaim;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperationStatus;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Contract implementation that models PostgreSQL conflict readback and lease fencing. */
public final class InMemoryProjectMemoryOperationStore implements ProjectMemoryOperationStore {
    private final ConcurrentMap<String, ProjectMemoryOperation> operations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RuntimeState> runtimeByKey = new ConcurrentHashMap<>();

    @Override
    public ProjectMemoryOperation register(ProjectMemoryOperation operation) {
        ProjectMemoryOperation existing = operations.putIfAbsent(operation.operationKey(), operation);
        if (existing == null) {
            runtimeByKey.putIfAbsent(operation.operationKey(), RuntimeState.pending(operation));
            return operation;
        }
        if (!sameImmutableInputs(existing, operation)) {
            throw new IllegalStateException("project memory operation key conflicts with immutable inputs");
        }
        return existing;
    }

    @Override
    public Optional<ProjectMemoryOperation> findByKey(String operationKey) {
        return Optional.ofNullable(operations.get(operationKey));
    }

    @Override
    public synchronized Optional<ProjectMemoryOperationClaim> claimNext(
            String owner,
            long nowEpochMillis,
            long leaseDurationMs
    ) {
        String normalizedOwner = requireOwner(owner);
        return runtimeByKey.entrySet().stream()
                .filter(entry -> operations.containsKey(entry.getKey()))
                .filter(entry -> entry.getValue().claimable(nowEpochMillis))
                .min(Comparator.comparing(entry -> operations.get(entry.getKey()).operationId()))
                .flatMap(entry -> claimLocked(entry.getKey(), normalizedOwner, nowEpochMillis, leaseDurationMs));
    }

    @Override
    public synchronized boolean settle(
            String operationId,
            String owner,
            long fencingToken,
            long rowVersion,
            ProjectMemoryOperationStatus terminalStatus
    ) {
        if (terminalStatus == null || !terminalStatus.terminal()) {
            throw new IllegalArgumentException("settle requires a terminal status");
        }
        return mutate(operationId, requireOwner(owner), fencingToken, rowVersion, state -> {
            state.status = terminalStatus;
            state.leaseOwner = "";
            state.leaseUntilEpochMillis = 0L;
            state.rowVersion += 1L;
            return true;
        });
    }

    @Override
    public synchronized boolean scheduleRetry(
            String operationId,
            String owner,
            long fencingToken,
            long rowVersion,
            long nextVisibleEpochMillis,
            String lastError
    ) {
        return mutate(operationId, requireOwner(owner), fencingToken, rowVersion, state -> {
            state.attemptNo += 1;
            state.status = ProjectMemoryOperationStatus.RETRYABLE;
            state.leaseOwner = "";
            state.leaseUntilEpochMillis = 0L;
            state.nextVisibleEpochMillis = Math.max(0L, nextVisibleEpochMillis);
            state.lastError = lastError == null ? "" : lastError.strip();
            state.rowVersion += 1L;
            return true;
        });
    }

    @Override
    public synchronized boolean markNeedsHuman(
            String operationId,
            String owner,
            long fencingToken,
            long rowVersion,
            String lastError
    ) {
        return mutate(operationId, requireOwner(owner), fencingToken, rowVersion, state -> {
            state.status = ProjectMemoryOperationStatus.NEEDS_HUMAN;
            state.leaseOwner = "";
            state.leaseUntilEpochMillis = 0L;
            state.lastError = lastError == null ? "" : lastError.strip();
            state.rowVersion += 1L;
            return true;
        });
    }

    @Override
    public synchronized boolean updateCheckpoint(
            String operationId,
            String owner,
            long fencingToken,
            long rowVersion,
            String checkpointJson
    ) {
        return mutate(operationId, requireOwner(owner), fencingToken, rowVersion, state -> {
            state.checkpointJson = checkpointJson == null || checkpointJson.isBlank() ? "{}" : checkpointJson.strip();
            state.rowVersion += 1L;
            return true;
        });
    }

    private Optional<ProjectMemoryOperationClaim> claimLocked(
            String operationKey,
            String owner,
            long nowEpochMillis,
            long leaseDurationMs
    ) {
        RuntimeState state = runtimeByKey.get(operationKey);
        ProjectMemoryOperation operation = operations.get(operationKey);
        if (state == null || operation == null || !state.claimable(nowEpochMillis)) {
            return Optional.empty();
        }
        state.status = ProjectMemoryOperationStatus.RUNNING;
        state.leaseOwner = owner;
        state.fencingToken += 1L;
        state.rowVersion += 1L;
        state.leaseUntilEpochMillis = nowEpochMillis + Math.max(1L, leaseDurationMs);
        return Optional.of(toClaim(operation, state));
    }

    private boolean mutate(
            String operationId,
            String owner,
            long fencingToken,
            long rowVersion,
            java.util.function.Function<RuntimeState, Boolean> mutation
    ) {
        Optional<RuntimeState> located = locate(operationId);
        if (located.isEmpty()) {
            return false;
        }
        RuntimeState state = located.get();
        ProjectMemoryOperation operation = operations.get(state.operationKey);
        if (operation == null || !state.leaseOwner.equals(owner) || state.fencingToken != fencingToken
                || state.rowVersion != rowVersion) {
            return false;
        }
        return mutation.apply(state);
    }

    private Optional<RuntimeState> locate(String operationId) {
        return runtimeByKey.values().stream()
                .filter(state -> operationId.equals(state.operationId))
                .findFirst();
    }

    private static ProjectMemoryOperationClaim toClaim(ProjectMemoryOperation operation, RuntimeState state) {
        return new ProjectMemoryOperationClaim(
                operation.operationId(),
                operation.projectId(),
                operation.kind(),
                operation.sourceIdentity(),
                operation.sourceContentHash(),
                operation.extractorVersion(),
                operation.schemaVersion(),
                operation.operationKey(),
                state.status,
                state.leaseOwner,
                state.fencingToken,
                state.rowVersion,
                state.attemptNo,
                state.maxAttempts,
                state.leaseUntilEpochMillis,
                state.nextVisibleEpochMillis,
                state.checkpointJson);
    }

    private static String requireOwner(String owner) {
        String normalized = owner == null ? "" : owner.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("lease owner must not be blank");
        }
        return normalized;
    }

    private static boolean sameImmutableInputs(ProjectMemoryOperation left, ProjectMemoryOperation right) {
        return left.projectId().equals(right.projectId())
                && left.kind().equals(right.kind())
                && left.sourceIdentity().equals(right.sourceIdentity())
                && left.sourceContentHash().equals(right.sourceContentHash())
                && left.extractorVersion().equals(right.extractorVersion())
                && left.schemaVersion().equals(right.schemaVersion());
    }

    private static final class RuntimeState {
        private String operationKey;
        private String operationId;
        private ProjectMemoryOperationStatus status = ProjectMemoryOperationStatus.PENDING;
        private String leaseOwner = "";
        private long fencingToken;
        private long rowVersion = 1L;
        private int attemptNo;
        private int maxAttempts = 3;
        private long leaseUntilEpochMillis;
        private long nextVisibleEpochMillis;
        private String checkpointJson = "{}";
        private String lastError = "";

        private static RuntimeState pending(ProjectMemoryOperation operation) {
            RuntimeState state = new RuntimeState();
            state.operationKey = operation.operationKey();
            state.operationId = operation.operationId();
            return state;
        }

        private boolean claimable(long nowEpochMillis) {
            if (status == ProjectMemoryOperationStatus.SUCCEEDED
                    || status == ProjectMemoryOperationStatus.FAILED
                    || status == ProjectMemoryOperationStatus.NEEDS_HUMAN) {
                return false;
            }
            if (status == ProjectMemoryOperationStatus.RUNNING) {
                return leaseUntilEpochMillis <= nowEpochMillis;
            }
            return (status == ProjectMemoryOperationStatus.PENDING || status == ProjectMemoryOperationStatus.RETRYABLE)
                    && nextVisibleEpochMillis <= nowEpochMillis;
        }

        private boolean leaseExpired(long nowEpochMillis) {
            return status == ProjectMemoryOperationStatus.RUNNING && leaseUntilEpochMillis < nowEpochMillis;
        }
    }
}
