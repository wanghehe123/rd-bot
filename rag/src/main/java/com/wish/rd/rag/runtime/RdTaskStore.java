package com.wish.rd.rag.runtime;

import java.util.List;
import java.util.Optional;

/**
 * RD 任务持久化端口。
 *
 * <p>rag 只依赖端口；内存实现用于测试和本地默认启动，PostgreSQL 适配器由 bootstrap 提供。
 */
public interface RdTaskStore {

    /**
     * 保存 Bug 修复任务快照。
     *
     * @param task 任务快照
     * @return 保存后的任务快照
     */
    RdBugFixTask saveBugFixTask(RdBugFixTask task);

    /**
     * 按任务 ID 查询 Bug 修复任务。
     *
     * @param taskId 任务 ID
     * @return 任务快照
     */
    Optional<RdBugFixTask> findBugFixTask(String taskId);

    /**
     * 查询所有 Bug 修复任务快照。
     *
     * @return 任务快照列表
     */
    List<RdBugFixTask> listBugFixTasks();
}
