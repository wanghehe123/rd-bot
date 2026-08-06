package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.RequirementPublicationRow;
import com.wish.rd.bootstrap.persistence.mapper.RequirementPublicationMapper;
import com.wish.rd.engine.requirement.publication.RequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.model.RequirementPublication;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL implementation of the requirement publication ledger store.
 *
 * <p>{@code insertPrepared} is idempotent on {@code operation_id}; {@code save}
 * uses version CAS so concurrent status advances cannot silently overwrite.
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresRequirementPublicationStore implements RequirementPublicationStore {

    private final RequirementPublicationMapper mapper;

    public PostgresRequirementPublicationStore(RequirementPublicationMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    @Transactional
    public RequirementPublication insertPrepared(RequirementPublication publication) {
        Objects.requireNonNull(publication, "publication must not be null");
        if (publication.status() != RequirementPublicationStatus.PREPARED) {
            throw new IllegalArgumentException("insertPrepared requires PREPARED status");
        }
        int inserted = mapper.insertPreparedIfAbsent(toRow(publication, null));
        if (inserted == 1) {
            return publication;
        }
        RequirementPublicationRow existing = mapper.selectByOperationId(publication.operationId());
        if (existing == null) {
            throw new IllegalStateException(
                    "publication conflict without existing row: " + publication.operationId());
        }
        return toPublication(existing);
    }

    @Override
    public Optional<RequirementPublication> findByOperationId(String operationId) {
        return Optional.ofNullable(mapper.selectByOperationId(safe(operationId)))
                .map(this::toPublication);
    }

    @Override
    public List<RequirementPublication> findDueForReconcile(long beforeEpochMillis, int limit) {
        int capped = Math.max(0, limit);
        if (capped == 0) {
            return List.of();
        }
        List<RequirementPublicationRow> rows = mapper.selectDueForReconcile(
                PostgresPersistenceSupport.toDateTime(Math.max(0L, beforeEpochMillis)),
                capped
        );
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream().map(this::toPublication).toList();
    }

    @Override
    @Transactional
    public RequirementPublication save(RequirementPublication publication) {
        Objects.requireNonNull(publication, "publication must not be null");
        RequirementPublicationRow current = mapper.selectByOperationId(publication.operationId());
        if (current == null) {
            throw new IllegalStateException("publication not found: " + publication.operationId());
        }
        if (!safe(current.id).equals(publication.id())) {
            throw new IllegalStateException(
                    "publication id mismatch for operation: " + publication.operationId());
        }
        int expectedVersion = Math.max(1, publication.version() - 1);
        RequirementPublicationRow update = toRow(publication, expectedVersion);
        if (mapper.updateWithExpectedVersion(update) != 1) {
            throw new IllegalStateException(
                    "publication compare-and-set failed: " + publication.operationId());
        }
        return publication;
    }

    private RequirementPublicationRow toRow(RequirementPublication publication, Integer expectedVersion) {
        RequirementPublicationRow row = new RequirementPublicationRow();
        row.id = publication.id();
        row.operationId = publication.operationId();
        row.taskId = PostgresPersistenceSupport.parseId(publication.taskId());
        row.stageRunId = safe(publication.stageRunId());
        row.status = publication.status().name();
        row.baseBranch = publication.baseBranch();
        row.workBranch = publication.workBranch();
        row.candidatePatchSha256 = publication.candidatePatchSha256();
        row.remoteHeadSha = safe(publication.remoteHeadSha());
        row.pullRequestUrl = safe(publication.pullRequestUrl());
        row.pullRequestNumber = publication.pullRequestNumber();
        row.version = publication.version();
        row.lastError = safe(publication.lastError());
        row.nextReconcileAt = PostgresPersistenceSupport.nullableDateTime(publication.nextReconcileAtEpochMillis());
        row.createdAt = PostgresPersistenceSupport.toDateTime(publication.createTimeEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(publication.updateTimeEpochMillis());
        row.expectedVersion = expectedVersion;
        return row;
    }

    private RequirementPublication toPublication(RequirementPublicationRow row) {
        return new RequirementPublication(
                safe(row.id),
                safe(row.operationId),
                PostgresPersistenceSupport.idString(row.taskId),
                safe(row.stageRunId),
                RequirementPublicationStatus.valueOf(safe(row.status)),
                safe(row.baseBranch),
                safe(row.workBranch),
                safe(row.candidatePatchSha256),
                safe(row.remoteHeadSha),
                safe(row.pullRequestUrl),
                row.pullRequestNumber == null ? 0 : row.pullRequestNumber,
                row.version == null ? 1 : row.version,
                safe(row.lastError),
                PostgresPersistenceSupport.toEpochMillis(row.nextReconcileAt),
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
