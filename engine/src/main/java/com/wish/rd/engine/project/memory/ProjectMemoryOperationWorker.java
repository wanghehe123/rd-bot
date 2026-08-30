package com.wish.rd.engine.project.memory;

import com.wish.rd.rag.project.memory.ProjectMemoryOperationStore;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperationClaim;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperationStatus;

import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/** Claims one durable operation, runs host-owned consolidation, and settles with fencing. */
public final class ProjectMemoryOperationWorker {
    private final ProjectMemoryOperationStore store;
    private final ProjectMemoryOperationHandler handler;
    private final long leaseDurationMs;
    private final long baseBackoffMs;
    private final LongSupplier clock;

    public ProjectMemoryOperationWorker(
            ProjectMemoryOperationStore store,
            ProjectMemoryOperationHandler handler,
            long leaseDurationMs,
            long baseBackoffMs,
            LongSupplier clock
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
        if (leaseDurationMs <= 0L) {
            throw new IllegalArgumentException("leaseDurationMs must be positive");
        }
        if (baseBackoffMs <= 0L) {
            throw new IllegalArgumentException("baseBackoffMs must be positive");
        }
        this.leaseDurationMs = leaseDurationMs;
        this.baseBackoffMs = baseBackoffMs;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public boolean runOnce(String owner) {
        long nowEpochMillis = clock.getAsLong();
        Optional<ProjectMemoryOperationClaim> claimed = store.claimNext(owner, nowEpochMillis, leaseDurationMs);
        if (claimed.isEmpty()) {
            return false;
        }
        ProjectMemoryOperationClaim claim = claimed.get();
        long rowVersion = claim.rowVersion();
        try {
            String checkpoint = handler.handle(claim);
            if (checkpoint != null && !checkpoint.equals(claim.checkpointJson())) {
                if (store.updateCheckpoint(
                        claim.operationId(),
                        owner,
                        claim.fencingToken(),
                        rowVersion,
                        checkpoint)) {
                    rowVersion += 1L;
                }
            }
            return store.settle(
                    claim.operationId(),
                    owner,
                    claim.fencingToken(),
                    rowVersion,
                    ProjectMemoryOperationStatus.SUCCEEDED);
        } catch (ProjectMemoryOperationRetryableException failure) {
            return handleRetryableFailure(claim, owner, nowEpochMillis, rowVersion, failure.getMessage());
        } catch (RuntimeException failure) {
            return handleRetryableFailure(claim, owner, nowEpochMillis, rowVersion, failure.getMessage());
        }
    }

    private boolean handleRetryableFailure(
            ProjectMemoryOperationClaim claim,
            String owner,
            long nowEpochMillis,
            long rowVersion,
            String message
    ) {
        int nextAttempt = claim.attemptNo() + 1;
        if (nextAttempt >= claim.maxAttempts()) {
            return store.markNeedsHuman(
                    claim.operationId(),
                    owner,
                    claim.fencingToken(),
                    rowVersion,
                    message == null ? "retry budget exhausted" : message);
        }
        long backoffMs = baseBackoffMs * (1L << Math.min(claim.attemptNo(), 10));
        return store.scheduleRetry(
                claim.operationId(),
                owner,
                claim.fencingToken(),
                rowVersion,
                nowEpochMillis + backoffMs,
                message == null ? "retryable failure" : message);
    }
}
