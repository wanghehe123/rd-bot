package com.wish.rd.rag.project.memory;

import com.wish.rd.rag.project.memory.model.ProjectMemoryOperation;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperationClaim;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperationStatus;

import java.util.Optional;

/** Durable-operation port with claim/lease/fencing settlement. */
public interface ProjectMemoryOperationStore {
    ProjectMemoryOperation register(ProjectMemoryOperation operation);

    Optional<ProjectMemoryOperation> findByKey(String operationKey);

    Optional<ProjectMemoryOperationClaim> claimNext(String owner, long nowEpochMillis, long leaseDurationMs);

    boolean settle(
            String operationId,
            String owner,
            long fencingToken,
            long rowVersion,
            ProjectMemoryOperationStatus terminalStatus
    );

    boolean scheduleRetry(
            String operationId,
            String owner,
            long fencingToken,
            long rowVersion,
            long nextVisibleEpochMillis,
            String lastError
    );

    boolean markNeedsHuman(
            String operationId,
            String owner,
            long fencingToken,
            long rowVersion,
            String lastError
    );

    boolean updateCheckpoint(
            String operationId,
            String owner,
            long fencingToken,
            long rowVersion,
            String checkpointJson
    );
}
