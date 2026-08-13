package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeDocumentRow;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeDocumentMapper;
import com.wish.rd.rag.ingestion.model.IngestionNodeLog;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentStatus;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentStore;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL 知识文档 Store。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresKnowledgeDocumentStore implements KnowledgeDocumentStore {

    private static final TypeReference<List<IngestionNodeLog>> NODE_LOGS_TYPE = new TypeReference<>() {
    };

    private final KnowledgeDocumentMapper mapper;
    private final ObjectMapper objectMapper;

    public PostgresKnowledgeDocumentStore(KnowledgeDocumentMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public KnowledgeDocument save(KnowledgeDocument document, String rawContent) {
        mapper.upsert(toRow(document, rawContent));
        return document;
    }

    @Override
    public Optional<KnowledgeDocument> findById(String id) {
        return Optional.ofNullable(mapper.selectById(PostgresPersistenceSupport.parseId(id)))
                .map(this::toDocument);
    }

    @Override
    public Optional<KnowledgeDocument> findBySource(
            String knowledgeBaseId,
            String sourceType,
            String sourceToken,
            String sourceUrl
    ) {
        String safeToken = PostgresPersistenceSupport.safe(sourceToken);
        String safeUrl = PostgresPersistenceSupport.safe(sourceUrl);
        if (safeToken.isBlank() && safeUrl.isBlank()) {
            return Optional.empty();
        }
        QueryWrapper<KnowledgeDocumentRow> query = new QueryWrapper<KnowledgeDocumentRow>()
                .eq("knowledge_base_id", PostgresPersistenceSupport.parseId(knowledgeBaseId))
                .eq("source_type", sourceType)
                .and(wrapper -> {
                    if (!safeToken.isBlank()) {
                        wrapper.eq("source_token", safeToken);
                    }
                    if (!safeUrl.isBlank()) {
                        if (!safeToken.isBlank()) {
                            wrapper.or();
                        }
                        wrapper.eq("source_url", safeUrl);
                    }
                });
        return mapper.selectList(query)
                .stream()
                .findFirst()
                .map(this::toDocument);
    }

    @Override
    public List<KnowledgeDocument> listByKnowledgeBaseId(String knowledgeBaseId) {
        return mapper.selectList(new QueryWrapper<KnowledgeDocumentRow>()
                        .eq("knowledge_base_id", PostgresPersistenceSupport.parseId(knowledgeBaseId)))
                .stream()
                .sorted(documentOrder())
                .map(this::toDocument)
                .toList();
    }

    @Override
    public List<KnowledgeDocument> listAll() {
        return mapper.selectList(null)
                .stream()
                .sorted(documentOrder())
                .map(this::toDocument)
                .toList();
    }

    @Override
    public String rawContent(String documentId) {
        KnowledgeDocumentRow row = mapper.selectById(PostgresPersistenceSupport.parseId(documentId));
        return row == null ? "" : PostgresPersistenceSupport.safe(row.rawContent);
    }

    @Override
    public void delete(String documentId) {
        mapper.deleteById(PostgresPersistenceSupport.parseId(documentId));
    }

    private KnowledgeDocumentRow toRow(KnowledgeDocument document, String rawContent) {
        OffsetDateTime now = OffsetDateTime.now();
        KnowledgeDocumentRow row = new KnowledgeDocumentRow();
        row.id = PostgresPersistenceSupport.parseId(document.id());
        row.knowledgeBaseId = PostgresPersistenceSupport.parseId(document.knowledgeBaseId());
        row.sourceName = document.sourceName();
        row.knowledgeType = document.knowledgeType();
        row.mimeType = document.mimeType();
        row.status = document.status().name();
        row.enabled = document.enabled();
        row.chunkCount = document.chunkCount();
        row.nodeLogsJson = toJson(document.nodeLogs());
        row.sourceType = document.sourceType();
        row.sourceToken = document.sourceToken();
        row.sourceUrl = document.sourceUrl();
        row.revisionId = document.revisionId();
        row.checksum = document.checksum();
        row.rawPreview = document.rawPreview();
        row.rawContent = rawContent;
        row.lastSyncedAt = PostgresPersistenceSupport.nullableDateTime(document.lastSyncedAtEpochMillis());
        row.nextRefreshAt = PostgresPersistenceSupport.nullableDateTime(document.nextRefreshAtEpochMillis());
        row.createdAt = PostgresPersistenceSupport.toDateTime(document.createdAtEpochMillis());
        row.updatedAt = now;
        row.syncVersion = document.syncVersion();
        row.currentRevisionId = PostgresPersistenceSupport.parseOptionalId(document.currentRevisionId());
        row.sourceIdentityKey = blankToNull(document.sourceIdentityKey());
        row.deletedAt = PostgresPersistenceSupport.nullableDateTime(document.deletedAtEpochMillis());
        row.purgeAfter = PostgresPersistenceSupport.nullableDateTime(document.purgeAfterEpochMillis());
        row.supersededByDocumentId = PostgresPersistenceSupport.parseOptionalId(document.supersededByDocumentId());
        row.rowVersion = document.rowVersion();
        row.localOnlyOverride = document.localOnlyOverride();
        return row;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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

    private Comparator<KnowledgeDocumentRow> documentOrder() {
        return Comparator
                .comparing((KnowledgeDocumentRow row) -> row.createdAt)
                .thenComparing(row -> row.id);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to serialize document node logs", exception);
        }
    }

    private List<IngestionNodeLog> fromJson(String value) {
        try {
            return objectMapper.readValue(value == null || value.isBlank() ? "[]" : value, NODE_LOGS_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to parse document node logs", exception);
        }
    }
}
