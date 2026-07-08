package com.wish.rd.bootstrap.persistence.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.persistence.entity.IngestionPipelineNodeRow;
import com.wish.rd.bootstrap.persistence.entity.IngestionPipelineRow;
import com.wish.rd.bootstrap.persistence.mapper.IngestionPipelineMapper;
import com.wish.rd.bootstrap.persistence.mapper.IngestionPipelineNodeMapper;
import com.wish.rd.rag.ingestion.IngestionPipelineStore;
import com.wish.rd.rag.ingestion.model.ManagedIngestionPipeline;
import com.wish.rd.rag.ingestion.model.ManagedIngestionPipelineNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * PostgreSQL 摄取管道存储适配器。
 *
 * <p>供管理台数据通道持久化 {@code t_ingestion_pipeline} 与节点拓扑，
 * 避免重启后管道退回 JVM 内存默认值。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresIngestionPipelineStore implements IngestionPipelineStore {

    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    private final IngestionPipelineMapper pipelineMapper;
    private final IngestionPipelineNodeMapper nodeMapper;

    public PostgresIngestionPipelineStore(
            IngestionPipelineMapper pipelineMapper,
            IngestionPipelineNodeMapper nodeMapper
    ) {
        this.pipelineMapper = pipelineMapper;
        this.nodeMapper = nodeMapper;
    }

    @Override
    public ManagedIngestionPipeline savePipeline(ManagedIngestionPipeline pipeline) {
        IngestionPipelineRow row = toRow(pipeline);
        IngestionPipelineRow existing = pipelineMapper.selectById(row.id);
        if (existing == null) {
            pipelineMapper.insert(row);
        } else {
            row.createTime = existing.createTime;
            row.createdBy = existing.createdBy;
            pipelineMapper.updateById(row);
        }
        nodeMapper.delete(new QueryWrapper<IngestionPipelineNodeRow>()
                .eq("pipeline_id", pipeline.id()));
        pipeline.nodes().stream()
                .map(node -> toNodeRow(pipeline, node))
                .forEach(nodeMapper::insertNode);
        return pipeline;
    }

    @Override
    public Optional<ManagedIngestionPipeline> findPipeline(String id) {
        return Optional.ofNullable(pipelineMapper.selectById(id))
                .filter(row -> row.deleted == null || row.deleted == 0)
                .map(this::toPipeline);
    }

    @Override
    public List<ManagedIngestionPipeline> listPipelines() {
        return pipelineMapper.selectList(new QueryWrapper<IngestionPipelineRow>().eq("deleted", 0))
                .stream()
                .sorted(Comparator.comparing((IngestionPipelineRow row) -> row.updateTime, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(row -> row.id))
                .map(this::toPipeline)
                .toList();
    }

    @Override
    public boolean deletePipeline(String id) {
        IngestionPipelineRow row = pipelineMapper.selectById(id);
        if (row == null || Integer.valueOf(1).equals(row.deleted)) {
            return false;
        }
        row.deleted = 1;
        row.updateTime = LocalDateTime.now();
        pipelineMapper.updateById(row);
        return true;
    }

    private IngestionPipelineRow toRow(ManagedIngestionPipeline pipeline) {
        IngestionPipelineRow row = new IngestionPipelineRow();
        row.id = pipeline.id();
        row.name = pipeline.name();
        row.description = pipeline.description();
        row.createdBy = pipeline.createdBy();
        row.updatedBy = "rd-bot";
        row.createTime = toDateTime(pipeline.createTimeEpochMillis());
        row.updateTime = toDateTime(pipeline.updateTimeEpochMillis());
        row.deleted = 0;
        return row;
    }

    private IngestionPipelineNodeRow toNodeRow(
            ManagedIngestionPipeline pipeline,
            ManagedIngestionPipelineNode node
    ) {
        LocalDateTime now = LocalDateTime.now();
        IngestionPipelineNodeRow row = new IngestionPipelineNodeRow();
        row.id = node.id();
        row.pipelineId = pipeline.id();
        row.nodeId = node.nodeId();
        row.nodeType = node.nodeType().toLowerCase(Locale.ROOT);
        row.nextNodeId = node.nextNodeId() == null ? "" : node.nextNodeId();
        row.settingsJson = "{}";
        row.conditionJson = "{}";
        row.createdBy = pipeline.createdBy();
        row.updatedBy = "rd-bot";
        row.createTime = now;
        row.updateTime = now;
        row.deleted = 0;
        return row;
    }

    private ManagedIngestionPipeline toPipeline(IngestionPipelineRow row) {
        List<ManagedIngestionPipelineNode> nodes = nodeMapper.selectList(new QueryWrapper<IngestionPipelineNodeRow>()
                        .eq("pipeline_id", row.id)
                        .eq("deleted", 0))
                .stream()
                .sorted(Comparator.comparing((IngestionPipelineNodeRow node) -> node.id))
                .map(this::toNode)
                .toList();
        return new ManagedIngestionPipeline(
                row.id,
                row.name,
                row.description,
                row.createdBy,
                nodes,
                toEpochMillis(row.createTime),
                toEpochMillis(row.updateTime)
        );
    }

    private ManagedIngestionPipelineNode toNode(IngestionPipelineNodeRow row) {
        return new ManagedIngestionPipelineNode(
                row.id,
                row.nodeId,
                normalizeNodeType(row.nodeType),
                row.nextNodeId == null || row.nextNodeId.isBlank() ? null : row.nextNodeId
        );
    }

    private String normalizeNodeType(String nodeType) {
        return nodeType == null ? "" : nodeType.strip().toUpperCase(Locale.ROOT);
    }

    private LocalDateTime toDateTime(long epochMillis) {
        if (epochMillis <= 0L) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), SYSTEM_ZONE);
    }

    private long toEpochMillis(LocalDateTime value) {
        if (value == null) {
            return 0L;
        }
        return value.atZone(SYSTEM_ZONE).toInstant().toEpochMilli();
    }
}
