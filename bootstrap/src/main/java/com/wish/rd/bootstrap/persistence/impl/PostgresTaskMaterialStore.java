package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.persistence.entity.RdTaskMaterialRow;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskMaterialMapper;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.TaskMaterialStore;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL 任务材料存储适配器。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresTaskMaterialStore implements TaskMaterialStore {

    private final RdTaskMaterialMapper materialMapper;

    public PostgresTaskMaterialStore(RdTaskMaterialMapper materialMapper) {
        this.materialMapper = materialMapper;
    }

    @Override
    public TaskMaterial save(TaskMaterial material) {
        materialMapper.upsertMaterial(toRow(material));
        return material;
    }

    @Override
    public Optional<TaskMaterial> findById(String materialId) {
        return Optional.ofNullable(materialMapper.selectById(PostgresPersistenceSupport.parseId(materialId)))
                .map(this::toMaterial);
    }

    @Override
    public List<TaskMaterial> listByTask(String taskId) {
        return materialMapper.selectList(new QueryWrapper<RdTaskMaterialRow>()
                        .eq("task_id", PostgresPersistenceSupport.parseId(taskId)))
                .stream()
                .sorted(Comparator
                        .comparing((RdTaskMaterialRow row) -> row.createdAt)
                        .thenComparing(row -> row.id))
                .map(this::toMaterial)
                .toList();
    }

    @Override
    public int deleteByTask(String taskId) {
        return materialMapper.delete(new QueryWrapper<RdTaskMaterialRow>()
                .eq("task_id", PostgresPersistenceSupport.parseId(taskId)));
    }

    private RdTaskMaterialRow toRow(TaskMaterial material) {
        RdTaskMaterialRow row = new RdTaskMaterialRow();
        row.id = PostgresPersistenceSupport.parseId(material.materialId());
        row.taskId = PostgresPersistenceSupport.parseId(material.taskId());
        row.materialType = material.materialType().name();
        row.sourceType = material.sourceType().name();
        row.title = material.title();
        row.sourceUri = material.sourceUri();
        row.mimeType = material.mimeType();
        row.contentHash = material.contentHash();
        row.contentPreview = material.contentPreview();
        row.artifactUri = material.artifactUri();
        row.knowledgeDocumentId = material.knowledgeDocumentId();
        row.revisionId = material.revisionId();
        row.metadataJson = material.metadataJson();
        row.createdAt = PostgresPersistenceSupport.toDateTime(material.createTimeEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(material.updateTimeEpochMillis());
        return row;
    }

    private TaskMaterial toMaterial(RdTaskMaterialRow row) {
        return new TaskMaterial(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.taskId),
                TaskMaterialType.valueOf(row.materialType),
                TaskMaterialSourceType.valueOf(row.sourceType),
                row.title,
                row.sourceUri,
                row.mimeType,
                row.contentHash,
                row.contentPreview,
                row.artifactUri,
                row.knowledgeDocumentId,
                row.revisionId,
                row.metadataJson,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }
}
