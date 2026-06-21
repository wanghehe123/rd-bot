package com.wish.rd.bootstrap.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeChunkRow;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeChunkMapper;
import com.wish.rd.rag.knowledge.KnowledgeChunk;
import com.wish.rd.rag.knowledge.store.KnowledgeChunkStore;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL 知识分块 Store。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresKnowledgeChunkStore implements KnowledgeChunkStore {

    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };

    private final KnowledgeChunkMapper mapper;
    private final ObjectMapper objectMapper;

    public PostgresKnowledgeChunkStore(KnowledgeChunkMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public KnowledgeChunk save(KnowledgeChunk chunk) {
        mapper.upsert(toRow(chunk));
        return chunk;
    }

    @Override
    public void saveAll(Collection<KnowledgeChunk> chunks) {
        if (chunks == null) {
            return;
        }
        chunks.forEach(this::save);
    }

    @Override
    public Optional<KnowledgeChunk> findById(String id) {
        return Optional.ofNullable(mapper.selectById(PostgresPersistenceSupport.parseId(id)))
                .map(this::toChunk);
    }

    @Override
    public List<KnowledgeChunk> listByDocumentId(String documentId) {
        return mapper.selectList(new QueryWrapper<KnowledgeChunkRow>()
                        .eq("document_id", PostgresPersistenceSupport.parseId(documentId)))
                .stream()
                .sorted(Comparator
                        .comparing((KnowledgeChunkRow row) -> row.chunkIndex)
                        .thenComparing(row -> row.id))
                .map(this::toChunk)
                .toList();
    }

    @Override
    public List<KnowledgeChunk> listAll() {
        return mapper.selectList(null)
                .stream()
                .sorted(Comparator
                        .comparing((KnowledgeChunkRow row) -> row.createdAt)
                        .thenComparing(row -> row.id))
                .map(this::toChunk)
                .toList();
    }

    @Override
    public void delete(String chunkId) {
        mapper.deleteById(PostgresPersistenceSupport.parseId(chunkId));
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        mapper.delete(new QueryWrapper<KnowledgeChunkRow>()
                .eq("document_id", PostgresPersistenceSupport.parseId(documentId)));
    }

    private KnowledgeChunkRow toRow(KnowledgeChunk chunk) {
        OffsetDateTime now = OffsetDateTime.now();
        KnowledgeChunkRow row = new KnowledgeChunkRow();
        row.id = PostgresPersistenceSupport.parseId(chunk.id());
        row.documentId = PostgresPersistenceSupport.parseId(chunk.documentId());
        row.knowledgeBaseId = PostgresPersistenceSupport.parseId(chunk.knowledgeBaseId());
        row.chunkIndex = chunk.index();
        row.content = chunk.content();
        row.contentHash = PostgresPersistenceSupport.checksum(chunk.content());
        row.knowledgeType = chunk.knowledgeType();
        row.sourceName = chunk.sourceName();
        row.enabled = chunk.enabled();
        row.metadataJson = toJson(chunk.metadata());
        row.createdAt = now;
        row.updatedAt = now;
        return row;
    }

    private KnowledgeChunk toChunk(KnowledgeChunkRow row) {
        return new KnowledgeChunk(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.documentId),
                PostgresPersistenceSupport.idString(row.knowledgeBaseId),
                row.chunkIndex == null ? 0 : row.chunkIndex,
                row.content,
                row.knowledgeType,
                row.sourceName,
                Boolean.TRUE.equals(row.enabled),
                fromJson(row.metadataJson)
        );
    }

    private String toJson(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to serialize chunk metadata", exception);
        }
    }

    private Map<String, String> fromJson(String value) {
        try {
            return objectMapper.readValue(value == null || value.isBlank() ? "{}" : value, STRING_MAP_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to parse chunk metadata", exception);
        }
    }
}
