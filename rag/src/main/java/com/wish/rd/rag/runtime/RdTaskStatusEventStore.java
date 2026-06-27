package com.wish.rd.rag.runtime;

import java.util.List;

/**
 * RD 任务状态事件持久化端口。
 *
 * <p>rag 只依赖端口；内存实现用于测试与本地默认启动，PostgreSQL 适配器由 bootstrap 提供。
 * 事件为 append-only，删除任务时按 {@link #deleteByTask(String)} 清理。
 */
public interface RdTaskStatusEventStore {

    /**
     * 追加一条状态事件。
     *
     * @param event 事件
     * @return 保存后的事件
     */
    RdTaskStatusEvent save(RdTaskStatusEvent event);

    /**
     * 按任务 ID 查询全部状态事件，按进入时间升序。
     *
     * @param taskId 任务 ID
     * @return 事件列表（升序）
     */
    List<RdTaskStatusEvent> listByTask(String taskId);

    /**
     * 删除某任务的全部状态事件（删除任务时调用）。
     *
     * @param taskId 任务 ID
     * @return 删除条数
     */
    int deleteByTask(String taskId);
}
