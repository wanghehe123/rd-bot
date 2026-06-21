package com.wish.rd.bootstrap.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeVectorRow;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeVectorMapper;
import com.wish.rd.framework.convention.RetrievedChunk;
import com.wish.rd.rag.vector.VectorStore;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL/pgvector 向量库实现。
 *
 * <p>P0 使用确定性词项哈希向量，保证无外部模型凭证也能完成写入、检索和联调。
 * 后续可在不改变 RAG 核心的前提下替换成真实 embedding 服务。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresVectorStore implements VectorStore {

    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };

    private final KnowledgeVectorMapper mapper;
    private final ObjectMapper objectMapper;
    private final int dimension;

    public PostgresVectorStore(
            KnowledgeVectorMapper mapper,
            ObjectMapper objectMapper,
            @Value("${rag.default.dimension:1536}") int dimension
    ) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.dimension = dimension <= 0 ? 1536 : dimension;
    }

    @Override
    public void index(Collection<RetrievedChunk> newChunks) {
        if (newChunks == null || newChunks.isEmpty()) {
            return;
        }
        newChunks.forEach(this::upsert);
    }

    @Override
    public void replace(RetrievedChunk chunk) {
        if (chunk == null) {
            return;
        }
        upsert(chunk);
    }

    @Override
    public void removeChunks(Collection<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        mapper.deleteBatchIds(chunkIds.stream().map(Long::parseLong).toList());
    }

    @Override
    public List<RetrievedChunk> vectorSearch(String query, Collection<String> knowledgeBaseIds, int topK) {
        String vectorLiteral = toVectorLiteral(embed(query));
        return mapper.vectorSearch(vectorLiteral, safeKnowledgeBaseIds(knowledgeBaseIds), safeLimit(topK))
                .stream()
                .map(this::toChunk)
                .toList();
    }

    @Override
    public List<RetrievedChunk> keywordSearch(String query, Collection<String> knowledgeBaseIds, int topK) {
        String keyword = "%" + safe(query).toLowerCase(Locale.ROOT) + "%";
        return mapper.keywordSearch(keyword, safeKnowledgeBaseIds(knowledgeBaseIds), safeLimit(topK))
                .stream()
                .map(this::toChunk)
                .toList();
    }

    @Override
    public List<RetrievedChunk> allChunks() {
        return mapper.selectAllForRetrieval()
                .stream()
                .map(this::toChunk)
                .toList();
    }

    private void upsert(RetrievedChunk chunk) {
        OffsetDateTime now = OffsetDateTime.now();
        KnowledgeVectorRow row = new KnowledgeVectorRow();
        row.id = Long.parseLong(chunk.chunkId());
        row.content = chunk.content();
        row.metadataJson = toJson(metadata(chunk));
        row.embedding = toVectorLiteral(embed(searchableText(chunk)));
        row.createdAt = now;
        row.updatedAt = now;
        mapper.upsert(row);
    }

    private RetrievedChunk toChunk(KnowledgeVectorRow row) {
        Map<String, String> metadata = fromJson(row.metadataJson);
        return new RetrievedChunk(
                PostgresPersistenceSupport.idString(row.id),
                row.content,
                metadata.getOrDefault("knowledgeBaseId", ""),
                metadata.getOrDefault("knowledgeType", "document"),
                metadata.getOrDefault("sourceName", ""),
                row.score == null ? 0.0d : row.score,
                metadata
        );
    }

    private Collection<String> safeKnowledgeBaseIds(Collection<String> knowledgeBaseIds) {
        Set<String> ids = knowledgeBaseIds == null ? Set.of() : Set.copyOf(knowledgeBaseIds);
        return ids;
    }

    private int safeLimit(int topK) {
        return topK <= 0 ? 10 : topK;
    }

    private Map<String, String> metadata(RetrievedChunk chunk) {
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>(chunk.metadata());
        metadata.put("knowledgeBaseId", chunk.knowledgeBaseId());
        metadata.put("knowledgeType", chunk.knowledgeType());
        metadata.put("sourceName", chunk.sourceName());
        return metadata;
    }

    private float[] embed(String text) {
        float[] vector = new float[dimension];
        String normalized = safe(text).toLowerCase(Locale.ROOT);
        for (String token : normalized.split("[^\\p{IsAlphabetic}\\p{IsDigit}_\\.]+")) {
            if (token.isBlank()) {
                continue;
            }
            int index = Math.floorMod(token.hashCode(), dimension);
            vector[index] += 1.0f;
        }
        normalize(vector);
        return vector;
    }

    private void normalize(float[] vector) {
        double sum = 0.0d;
        for (float value : vector) {
            sum += value * value;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0.0d) {
            vector[0] = 1.0f;
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
    }

    private String searchableText(RetrievedChunk chunk) {
        return String.join("\n", chunk.sourceName(), chunk.knowledgeType(), chunk.content());
    }

    private String toVectorLiteral(float[] embedding) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(embedding[i]);
        }
        return builder.append(']').toString();
    }

    private String toJson(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to serialize vector metadata", exception);
        }
    }

    private Map<String, String> fromJson(String value) {
        try {
            return objectMapper.readValue(value == null || value.isBlank() ? "{}" : value, STRING_MAP_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to parse vector metadata", exception);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
