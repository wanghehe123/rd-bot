package com.wish.rd.bootstrap.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeRefreshMetricRow;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeRefreshMetricMapper;
import com.wish.rd.rag.knowledge.KnowledgeRefreshMetric;
import com.wish.rd.rag.knowledge.KnowledgeRefreshMetricQueryPort;
import com.wish.rd.rag.knowledge.KnowledgeRefreshMetricSink;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL sink/query adapter for knowledge refresh metrics.
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresKnowledgeRefreshMetricStore implements KnowledgeRefreshMetricSink, KnowledgeRefreshMetricQueryPort {

    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };

    private final KnowledgeRefreshMetricMapper mapper;
    private final ObjectMapper objectMapper;

    public PostgresKnowledgeRefreshMetricStore(KnowledgeRefreshMetricMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(KnowledgeRefreshMetric metric) {
        mapper.insertMetric(toRow(metric));
    }

    @Override
    public List<KnowledgeRefreshMetric> metrics() {
        return mapper.selectList(new QueryWrapper<KnowledgeRefreshMetricRow>().orderByDesc("created_at", "id"))
                .stream()
                .sorted(Comparator
                        .comparing((KnowledgeRefreshMetricRow row) -> row.createdAt).reversed()
                        .thenComparing(row -> row.id, Comparator.reverseOrder()))
                .map(this::toMetric)
                .toList();
    }

    private KnowledgeRefreshMetricRow toRow(KnowledgeRefreshMetric metric) {
        KnowledgeRefreshMetric safe = metric == null
                ? new KnowledgeRefreshMetric("", "", "", "", false, 0, 0, 0L, "", Map.of(), 0L)
                : metric;
        KnowledgeRefreshMetricRow row = new KnowledgeRefreshMetricRow();
        row.documentId = safe.documentId();
        row.knowledgeBaseId = safe.knowledgeBaseId();
        row.sourceType = safe.sourceType();
        row.sourceName = safe.sourceName();
        row.success = safe.success();
        row.oldChunkCount = safe.oldChunkCount();
        row.newChunkCount = safe.newChunkCount();
        row.durationMs = safe.durationMillis();
        row.errorMessage = safe.errorMessage();
        row.metadataJson = writeJson(safe.metadata());
        row.createdAt = PostgresPersistenceSupport.toDateTime(safe.createdAtEpochMillis());
        return row;
    }

    private KnowledgeRefreshMetric toMetric(KnowledgeRefreshMetricRow row) {
        return new KnowledgeRefreshMetric(
                PostgresPersistenceSupport.safe(row.documentId),
                PostgresPersistenceSupport.safe(row.knowledgeBaseId),
                PostgresPersistenceSupport.safe(row.sourceType),
                PostgresPersistenceSupport.safe(row.sourceName),
                Boolean.TRUE.equals(row.success),
                row.oldChunkCount == null ? 0 : row.oldChunkCount,
                row.newChunkCount == null ? 0 : row.newChunkCount,
                row.durationMs == null ? 0L : row.durationMs,
                PostgresPersistenceSupport.safe(row.errorMessage),
                readMap(row.metadataJson),
                PostgresPersistenceSupport.toEpochMillis(row.createdAt)
        );
    }

    private String writeJson(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private Map<String, String> readMap(String value) {
        try {
            return objectMapper.readValue(PostgresPersistenceSupport.safe(value).isBlank() ? "{}" : value, STRING_MAP_TYPE);
        } catch (Exception exception) {
            return Map.of();
        }
    }
}
