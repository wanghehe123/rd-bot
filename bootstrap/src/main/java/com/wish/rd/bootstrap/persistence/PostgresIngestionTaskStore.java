package com.wish.rd.bootstrap.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.IngestionTaskNodeRow;
import com.wish.rd.bootstrap.persistence.entity.IngestionTaskRow;
import com.wish.rd.bootstrap.persistence.mapper.IngestionTaskMapper;
import com.wish.rd.bootstrap.persistence.mapper.IngestionTaskNodeMapper;
import com.wish.rd.rag.ingestion.IngestionStatus;
import com.wish.rd.rag.ingestion.IngestionTaskStore;
import com.wish.rd.rag.ingestion.ManagedIngestionTask;
import com.wish.rd.rag.ingestion.ManagedIngestionTaskNode;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PostgreSQL 摄取任务 Store。
 */
public final class PostgresIngestionTaskStore implements IngestionTaskStore {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP_TYPE = new TypeReference<>() {
    };

    private final IngestionTaskMapper taskMapper;
    private final IngestionTaskNodeMapper nodeMapper;
    private final ObjectMapper objectMapper;

    public PostgresIngestionTaskStore(
            IngestionTaskMapper taskMapper,
            IngestionTaskNodeMapper nodeMapper,
            ObjectMapper objectMapper
    ) {
        this.taskMapper = taskMapper;
        this.nodeMapper = nodeMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveTask(ManagedIngestionTask task) {
        taskMapper.upsert(toRow(task));
    }

    @Override
    public void saveTaskNodes(String taskId, List<ManagedIngestionTaskNode> nodes) {
        nodeMapper.delete(new QueryWrapper<IngestionTaskNodeRow>()
                .eq("task_id", PostgresPersistenceSupport.parseId(taskId)));
        if (nodes == null) {
            return;
        }
        nodes.stream()
                .map(this::toRow)
                .forEach(nodeMapper::insertNode);
    }

    @Override
    public Optional<ManagedIngestionTask> findTask(String taskId) {
        return Optional.ofNullable(taskMapper.selectById(PostgresPersistenceSupport.parseId(taskId)))
                .map(this::toTask);
    }

    @Override
    public List<ManagedIngestionTaskNode> listTaskNodes(String taskId) {
        return nodeMapper.selectList(new QueryWrapper<IngestionTaskNodeRow>()
                        .eq("task_id", PostgresPersistenceSupport.parseId(taskId)))
                .stream()
                .sorted(Comparator
                        .comparing((IngestionTaskNodeRow row) -> row.nodeOrder)
                        .thenComparing(row -> row.id))
                .map(this::toNode)
                .toList();
    }

    @Override
    public List<ManagedIngestionTask> listTasks() {
        return taskMapper.selectList(null)
                .stream()
                .sorted(Comparator
                        .comparing((IngestionTaskRow row) -> row.createdAt, Comparator.reverseOrder())
                        .thenComparing((IngestionTaskRow row) -> row.id, Comparator.reverseOrder()))
                .map(this::toTask)
                .toList();
    }

    private IngestionTaskRow toRow(ManagedIngestionTask task) {
        IngestionTaskRow row = new IngestionTaskRow();
        row.id = PostgresPersistenceSupport.parseId(task.id());
        row.pipelineId = task.pipelineId();
        row.knowledgeBaseId = PostgresPersistenceSupport.parseId(task.knowledgeBaseId());
        row.documentId = blankToNullId(task.documentId());
        row.sourceType = task.sourceType();
        row.sourceLocation = task.sourceLocation();
        row.sourceFileName = task.sourceFileName();
        row.status = task.status().name();
        row.chunkCount = task.chunkCount();
        row.errorMessage = task.errorMessage();
        row.metadataJson = toJson(task.metadata());
        row.startedAt = PostgresPersistenceSupport.toDateTime(task.startedAtEpochMillis());
        row.completedAt = PostgresPersistenceSupport.nullableDateTime(task.completedAtEpochMillis());
        row.createdBy = task.createdBy();
        row.createdAt = PostgresPersistenceSupport.toDateTime(task.createTimeEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(task.updateTimeEpochMillis());
        return row;
    }

    private IngestionTaskNodeRow toRow(ManagedIngestionTaskNode node) {
        IngestionTaskNodeRow row = new IngestionTaskNodeRow();
        row.id = PostgresPersistenceSupport.parseId(node.id());
        row.taskId = PostgresPersistenceSupport.parseId(node.taskId());
        row.pipelineId = node.pipelineId();
        row.nodeId = node.nodeId();
        row.nodeType = node.nodeType();
        row.nodeOrder = node.nodeOrder();
        row.status = node.status();
        row.durationMs = node.durationMs();
        row.message = node.message();
        row.errorMessage = node.errorMessage();
        row.outputJson = toJson(node.output());
        row.createdAt = PostgresPersistenceSupport.toDateTime(node.createTimeEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(node.updateTimeEpochMillis());
        return row;
    }

    private ManagedIngestionTask toTask(IngestionTaskRow row) {
        return new ManagedIngestionTask(
                PostgresPersistenceSupport.idString(row.id),
                row.pipelineId,
                PostgresPersistenceSupport.idString(row.knowledgeBaseId),
                PostgresPersistenceSupport.idString(row.documentId),
                row.sourceType,
                row.sourceLocation,
                row.sourceFileName,
                IngestionStatus.valueOf(row.status),
                row.chunkCount == null ? 0 : row.chunkCount,
                row.errorMessage,
                fromJson(row.metadataJson),
                PostgresPersistenceSupport.toEpochMillis(row.startedAt),
                nullableEpoch(row.completedAt),
                row.createdBy,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }

    private ManagedIngestionTaskNode toNode(IngestionTaskNodeRow row) {
        return new ManagedIngestionTaskNode(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.taskId),
                row.pipelineId,
                row.nodeId,
                row.nodeType,
                row.nodeOrder == null ? 0 : row.nodeOrder,
                row.status,
                row.durationMs == null ? 0L : row.durationMs,
                row.message,
                row.errorMessage,
                fromJson(row.outputJson),
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }

    private Long blankToNullId(String value) {
        return value == null || value.isBlank() ? null : PostgresPersistenceSupport.parseId(value);
    }

    private Long nullableEpoch(java.time.OffsetDateTime value) {
        long epochMillis = PostgresPersistenceSupport.toEpochMillis(value);
        return epochMillis <= 0L ? null : epochMillis;
    }

    private String toJson(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to serialize ingestion json", exception);
        }
    }

    private Map<String, Object> fromJson(String value) {
        try {
            return objectMapper.readValue(value == null || value.isBlank() ? "{}" : value, OBJECT_MAP_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to parse ingestion json", exception);
        }
    }
}
