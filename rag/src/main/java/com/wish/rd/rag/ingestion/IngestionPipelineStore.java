package com.wish.rd.rag.ingestion;

import com.wish.rd.rag.ingestion.model.ManagedIngestionPipeline;

import java.util.List;
import java.util.Optional;

/**
 * 摄取管道存储端口。
 *
 * <p>由 {@link IngestionAdminRegistry} 依赖，生产环境通过 PostgreSQL 保留管道拓扑；
 * memory 模式仅用于本地零配置和单元测试。
 */
public interface IngestionPipelineStore {

    /**
     * 保存摄取管道及其节点拓扑。
     *
     * @param pipeline 管道
     * @return 已保存管道
     */
    ManagedIngestionPipeline savePipeline(ManagedIngestionPipeline pipeline);

    /**
     * 按 ID 查询管道。
     *
     * @param id 管道 ID
     * @return 管道
     */
    Optional<ManagedIngestionPipeline> findPipeline(String id);

    /**
     * 查询全部未删除管道。
     *
     * @return 管道快照
     */
    List<ManagedIngestionPipeline> listPipelines();

    /**
     * 删除管道。
     *
     * @param id 管道 ID
     * @return 是否删除成功
     */
    boolean deletePipeline(String id);
}
