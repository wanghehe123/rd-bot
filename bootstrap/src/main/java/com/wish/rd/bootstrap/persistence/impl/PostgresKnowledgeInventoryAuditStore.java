package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.InventoryCategoryCountRow;
import com.wish.rd.bootstrap.persistence.entity.InventoryDriftRow;
import com.wish.rd.bootstrap.persistence.entity.InventoryDuplicateMemberRow;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeDocumentRow;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeInventoryAuditMapper;
import com.wish.rd.rag.ingestion.model.IngestionNodeLog;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentStatus;
import com.wish.rd.rag.knowledge.projection.KnowledgeInventoryAuditStore;
import com.wish.rd.rag.knowledge.projection.model.DuplicateIdentityGroup;
import com.wish.rd.rag.knowledge.projection.model.DuplicateIdentityMember;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.InventoryCategory;
import com.wish.rd.rag.knowledge.projection.model.InventoryCategoryCounts;
import com.wish.rd.rag.knowledge.projection.model.InventoryDriftEntry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/**
 * PostgreSQL 存量审计只读实现。分类 SQL 顺序必须与 D5 一致。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresKnowledgeInventoryAuditStore implements KnowledgeInventoryAuditStore {

    private static final TypeReference<List<IngestionNodeLog>> NODE_LOGS_TYPE = new TypeReference<>() {
    };

    private final KnowledgeInventoryAuditMapper mapper;
    private final ObjectMapper objectMapper;

    public PostgresKnowledgeInventoryAuditStore(KnowledgeInventoryAuditMapper mapper, ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public InventoryCategoryCounts countByCategory(String knowledgeBaseId) {
        EnumMap<InventoryCategory, Long> counts = new EnumMap<>(InventoryCategory.class);
        for (InventoryCategoryCountRow row : mapper.countByCategory(parseId(knowledgeBaseId))) {
            if (row.category == null || row.category.isBlank()) {
                continue;
            }
            counts.merge(InventoryCategory.valueOf(row.category),
                    row.documentCount == null ? 0L : row.documentCount, Long::sum);
        }
        return InventoryCategoryCounts.from(counts);
    }

    @Override
    public long countDocuments(String knowledgeBaseId) {
        return mapper.countDocuments(parseId(knowledgeBaseId));
    }

    @Override
    public List<KnowledgeDocument> nextBackfillCandidates(String knowledgeBaseId, String afterDocumentId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        long afterId = afterDocumentId == null || afterDocumentId.isBlank()
                ? 0L
                : PostgresPersistenceSupport.parseId(afterDocumentId);
        return mapper.nextBackfillCandidates(parseId(knowledgeBaseId), afterId, limit).stream()
                .map(this::toDocument)
                .toList();
    }

    @Override
    public List<DuplicateIdentityGroup> listDuplicateGroups(String knowledgeBaseId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        ArrayList<DuplicateIdentityGroup> groups = new ArrayList<>();
        String currentKey = null;
        ArrayList<DuplicateIdentityMember> members = new ArrayList<>();
        for (InventoryDuplicateMemberRow row : mapper.listDuplicateMembers(parseId(knowledgeBaseId))) {
            String identity = row.identity == null ? "" : row.identity;
            if (currentKey != null && !currentKey.equals(identity)) {
                groups.add(group(knowledgeBaseId, currentKey, members));
                members = new ArrayList<>();
                if (groups.size() >= limit) {
                    return List.copyOf(groups);
                }
            }
            currentKey = identity;
            members.add(new DuplicateIdentityMember(
                    PostgresPersistenceSupport.idString(row.id),
                    PostgresPersistenceSupport.toEpochMillis(row.lastSyncedAt),
                    PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                    row.rowVersion == null ? 0L : row.rowVersion
            ));
        }
        if (currentKey != null && groups.size() < limit) {
            groups.add(group(knowledgeBaseId, currentKey, members));
        }
        return List.copyOf(groups);
    }

    @Override
    public List<InventoryDriftEntry> listLocalOrphanDrift(String knowledgeBaseId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        ArrayList<InventoryDriftEntry> entries = new ArrayList<>();
        for (InventoryDriftRow row : mapper.listLocalOrphanDrift(parseId(knowledgeBaseId), limit)) {
            entries.add(new InventoryDriftEntry(
                    PostgresPersistenceSupport.idString(row.knowledgeBaseId),
                    PostgresPersistenceSupport.idString(row.documentId),
                    row.remoteUri,
                    row.desiredState == null
                            ? ExternalKnowledgeDesiredState.PRESENT
                            : ExternalKnowledgeDesiredState.valueOf(row.desiredState)
            ));
        }
        return List.copyOf(entries);
    }

    @Override
    public long countInFlightOperations(String knowledgeBaseId) {
        return mapper.countInFlightOperations(parseId(knowledgeBaseId));
    }

    private DuplicateIdentityGroup group(
            String knowledgeBaseId,
            String identityKey,
            List<DuplicateIdentityMember> members
    ) {
        return new DuplicateIdentityGroup(
                knowledgeBaseId,
                identityKey,
                members.isEmpty() ? "" : members.getFirst().documentId(),
                List.copyOf(members)
        );
    }

    private KnowledgeDocument toDocument(KnowledgeDocumentRow row) {
        return new KnowledgeDocument(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.knowledgeBaseId),
                row.sourceName,
                row.knowledgeType,
                row.mimeType,
                KnowledgeDocumentStatus.valueOf(row.status),
                Boolean.TRUE.equals(row.enabled),
                row.chunkCount == null ? 0 : row.chunkCount,
                fromJson(row.nodeLogsJson),
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                row.sourceType,
                row.sourceToken,
                row.sourceUrl,
                row.revisionId,
                row.checksum,
                row.rawPreview,
                PostgresPersistenceSupport.toEpochMillis(row.lastSyncedAt),
                PostgresPersistenceSupport.toEpochMillis(row.nextRefreshAt),
                row.syncVersion == null ? 1L : row.syncVersion,
                PostgresPersistenceSupport.idString(row.currentRevisionId),
                row.sourceIdentityKey == null ? "" : row.sourceIdentityKey,
                PostgresPersistenceSupport.toEpochMillis(row.deletedAt),
                PostgresPersistenceSupport.toEpochMillis(row.purgeAfter),
                PostgresPersistenceSupport.idString(row.supersededByDocumentId),
                row.rowVersion == null ? 0L : row.rowVersion,
                Boolean.TRUE.equals(row.localOnlyOverride)
        );
    }

    private List<IngestionNodeLog> fromJson(String value) {
        try {
            return objectMapper.readValue(value == null || value.isBlank() ? "[]" : value, NODE_LOGS_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to parse document node logs", exception);
        }
    }

    private static Long parseId(String knowledgeBaseId) {
        return PostgresPersistenceSupport.parseId(knowledgeBaseId);
    }
}
