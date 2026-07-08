package com.wish.rd.rag.ingestion.impl;

import com.wish.rd.rag.ingestion.IngestionPipelineStore;
import com.wish.rd.rag.ingestion.model.ManagedIngestionPipeline;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * memory 模式下的摄取管道存储。
 *
 * <p>仅用于显式 {@code rd.knowledge.store=memory} 或单元测试，生产可见管道
 * 必须经由 PostgreSQL 适配器持久化。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public final class InMemoryIngestionPipelineStore implements IngestionPipelineStore {

    private final LinkedHashMap<String, ManagedIngestionPipeline> pipelines = new LinkedHashMap<>();

    @Override
    public synchronized ManagedIngestionPipeline savePipeline(ManagedIngestionPipeline pipeline) {
        pipelines.put(pipeline.id(), pipeline);
        return pipeline;
    }

    @Override
    public synchronized Optional<ManagedIngestionPipeline> findPipeline(String id) {
        return Optional.ofNullable(pipelines.get(id));
    }

    @Override
    public synchronized List<ManagedIngestionPipeline> listPipelines() {
        return List.copyOf(pipelines.values());
    }

    @Override
    public synchronized boolean deletePipeline(String id) {
        return pipelines.remove(id) != null;
    }
}
