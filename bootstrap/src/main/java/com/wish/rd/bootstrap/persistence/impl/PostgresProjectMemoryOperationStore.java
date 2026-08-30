package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.ProjectMemoryOperationRow;
import com.wish.rd.bootstrap.persistence.mapper.ProjectMemoryOperationMapper;
import com.wish.rd.rag.project.memory.ProjectMemoryOperationStore;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperation;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperationClaim;
import com.wish.rd.rag.project.memory.model.ProjectMemoryOperationStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresProjectMemoryOperationStore implements ProjectMemoryOperationStore {
    private final ProjectMemoryOperationMapper mapper;

    public PostgresProjectMemoryOperationStore(ProjectMemoryOperationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ProjectMemoryOperation register(ProjectMemoryOperation operation) {
        ProjectMemoryOperationRow row = row(operation);
        if (mapper.insertIfAbsent(row) == 1) {
            return operation;
        }
        ProjectMemoryOperationRow existing = mapper.findForUpdate(operation.operationKey());
        if (existing == null
                || existing.projectId != PostgresPersistenceSupport.parseId(operation.projectId())
                || !existing.sourceIdentity.equals(operation.sourceIdentity())
                || !existing.sourceContentHash.equals(operation.sourceContentHash())
                || !existing.operationKind.equals(operation.kind())
                || !existing.extractorVersion.equals(operation.extractorVersion())
                || !existing.schemaVersion.equals(operation.schemaVersion())) {
            throw new IllegalStateException("project memory operation key conflicts with immutable inputs");
        }
        return model(existing);
    }

    @Override
    public Optional<ProjectMemoryOperation> findByKey(String operationKey) {
        ProjectMemoryOperationRow row = mapper.findForUpdate(operationKey);
        return Optional.ofNullable(row).map(this::model);
    }

    @Override
    @Transactional
    public Optional<ProjectMemoryOperationClaim> claimNext(String owner, long nowEpochMillis, long leaseDurationMs) {
        ProjectMemoryOperationRow candidate = mapper.claimNext();
        if (candidate == null) {
            return Optional.empty();
        }
        long leaseSeconds = Math.max(1L, leaseDurationMs / 1000L);
        if (mapper.claim(candidate.id, requireOwner(owner), candidate.rowVersion, leaseSeconds) != 1) {
            return Optional.empty();
        }
        ProjectMemoryOperationRow refreshed = mapper.findById(candidate.id);
        return Optional.ofNullable(refreshed).map(this::claim);
    }

    @Override
    public boolean settle(
            String operationId,
            String owner,
            long fencingToken,
            long rowVersion,
            ProjectMemoryOperationStatus terminalStatus
    ) {
        if (terminalStatus == null || !terminalStatus.terminal()) {
            throw new IllegalArgumentException("settle requires a terminal status");
        }
        return mapper.settle(
                PostgresPersistenceSupport.parseId(operationId),
                requireOwner(owner),
                fencingToken,
                rowVersion,
                terminalStatus.name()) == 1;
    }

    @Override
    public boolean scheduleRetry(
            String operationId,
            String owner,
            long fencingToken,
            long rowVersion,
            long nextVisibleEpochMillis,
            String lastError
    ) {
        return mapper.scheduleRetry(
                PostgresPersistenceSupport.parseId(operationId),
                requireOwner(owner),
                fencingToken,
                rowVersion,
                nextVisibleEpochMillis,
                lastError == null ? "" : lastError.strip()) == 1;
    }

    @Override
    public boolean markNeedsHuman(
            String operationId,
            String owner,
            long fencingToken,
            long rowVersion,
            String lastError
    ) {
        return mapper.markNeedsHuman(
                PostgresPersistenceSupport.parseId(operationId),
                requireOwner(owner),
                fencingToken,
                rowVersion,
                lastError == null ? "" : lastError.strip()) == 1;
    }

    @Override
    public boolean updateCheckpoint(
            String operationId,
            String owner,
            long fencingToken,
            long rowVersion,
            String checkpointJson
    ) {
        String normalized = checkpointJson == null || checkpointJson.isBlank() ? "{}" : checkpointJson.strip();
        return mapper.updateCheckpoint(
                PostgresPersistenceSupport.parseId(operationId),
                requireOwner(owner),
                fencingToken,
                rowVersion,
                normalized) == 1;
    }

    private ProjectMemoryOperationRow row(ProjectMemoryOperation operation) {
        ProjectMemoryOperationRow row = new ProjectMemoryOperationRow();
        row.id = PostgresPersistenceSupport.parseId(operation.operationId());
        row.operationKey = operation.operationKey();
        row.projectId = PostgresPersistenceSupport.parseId(operation.projectId());
        row.operationKind = operation.kind();
        row.sourceIdentity = operation.sourceIdentity();
        row.sourceContentHash = operation.sourceContentHash();
        row.extractorVersion = operation.extractorVersion();
        row.schemaVersion = operation.schemaVersion();
        return row;
    }

    private ProjectMemoryOperation model(ProjectMemoryOperationRow row) {
        return new ProjectMemoryOperation(
                row.id.toString(),
                row.projectId.toString(),
                row.operationKind,
                row.sourceIdentity,
                row.sourceContentHash,
                row.extractorVersion,
                row.schemaVersion,
                row.operationKey);
    }

    private ProjectMemoryOperationClaim claim(ProjectMemoryOperationRow row) {
        return new ProjectMemoryOperationClaim(
                row.id.toString(),
                row.projectId.toString(),
                row.operationKind,
                row.sourceIdentity,
                row.sourceContentHash,
                row.extractorVersion,
                row.schemaVersion,
                row.operationKey,
                ProjectMemoryOperationStatus.valueOf(row.status),
                row.leaseOwner == null ? "" : row.leaseOwner,
                row.fencingToken == null ? 0L : row.fencingToken,
                row.rowVersion == null ? 1L : row.rowVersion,
                row.attemptNo == null ? 0 : row.attemptNo,
                row.maxAttempts == null ? 3 : row.maxAttempts,
                epochMillis(row.leaseUntil),
                epochMillis(row.nextVisibleAt),
                row.checkpointJson == null ? "{}" : row.checkpointJson);
    }

    private static long epochMillis(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }

    private static String requireOwner(String owner) {
        String normalized = owner == null ? "" : owner.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("lease owner must not be blank");
        }
        return normalized;
    }
}
