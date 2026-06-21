package com.wish.rd.engine.admin.ingestion;

import com.wish.rd.rag.ingestion.IngestionAdminRegistry;
import com.wish.rd.rag.ingestion.IngestionPipelineCommand;
import com.wish.rd.rag.ingestion.IngestionPipelinePage;
import com.wish.rd.rag.ingestion.IngestionTaskPage;
import com.wish.rd.rag.ingestion.ManagedIngestionPipeline;
import com.wish.rd.rag.ingestion.ManagedIngestionTask;
import com.wish.rd.rag.ingestion.ManagedIngestionTaskCommand;
import com.wish.rd.rag.ingestion.ManagedIngestionTaskNode;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 摄取管理业务编排引擎。
 *
 * <p>封装 {@link IngestionAdminRegistry} 的管线与任务管理能力
 * （增删改查、分页、执行任务、节点列表），供 {@code IngestionAdminController} 调用。
 */
@Service
public final class IngestionAdminEngine {

    private final IngestionAdminRegistry registry;

    public IngestionAdminEngine(IngestionAdminRegistry registry) {
        this.registry = registry;
    }

    public ManagedIngestionPipeline createPipeline(IngestionPipelineCommand command) {
        return registry.createPipeline(command);
    }

    public ManagedIngestionPipeline updatePipeline(String id, IngestionPipelineCommand command) {
        return registry.updatePipeline(id, command);
    }

    public ManagedIngestionPipeline getPipeline(String id) {
        return registry.getPipeline(id);
    }

    public IngestionPipelinePage pagePipelines(String keyword, int pageNo, int pageSize) {
        return registry.pagePipelines(keyword, pageNo, pageSize);
    }

    public boolean deletePipeline(String id) {
        return registry.deletePipeline(id);
    }

    public ManagedIngestionTask executeTask(ManagedIngestionTaskCommand command) {
        return registry.executeTask(command);
    }

    public ManagedIngestionTask getTask(String id) {
        return registry.getTask(id);
    }

    public List<ManagedIngestionTaskNode> listTaskNodes(String id) {
        return registry.listTaskNodes(id);
    }

    public IngestionTaskPage pageTasks(String status, int pageNo, int pageSize) {
        return registry.pageTasks(status, pageNo, pageSize);
    }
}
