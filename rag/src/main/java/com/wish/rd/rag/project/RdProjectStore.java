package com.wish.rd.rag.project;

import java.util.List;
import java.util.Optional;
import com.wish.rd.rag.project.model.RdProject;

/**
 * RD 项目持久化端口。
 *
 * <p>项目配置是生产共享状态，只允许通过数据库适配器实现；本端口不提供 in-memory 默认实现。
 */
public interface RdProjectStore {

    /**
     * 保存项目快照。
     *
     * @param project 项目快照
     * @return 保存后的项目快照
     */
    RdProject save(RdProject project);

    /**
     * 按项目 ID 查询。
     *
     * @param projectId 项目 ID
     * @return 项目快照
     */
    Optional<RdProject> findById(String projectId);

    /**
     * 按项目 key 查询。
     *
     * @param projectKey 项目 key
     * @return 项目快照
     */
    Optional<RdProject> findByKey(String projectKey);

    /**
     * 查询全部项目快照。
     *
     * @return 项目列表
     */
    List<RdProject> list();
}
