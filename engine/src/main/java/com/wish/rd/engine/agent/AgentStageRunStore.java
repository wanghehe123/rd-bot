package com.wish.rd.engine.agent;

import java.util.List;
import java.util.Optional;
import java.util.LinkedHashSet;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;

/**
 * Agent 阶段运行存储端口。
 *
 * <p>生产实现应使用 PostgreSQL 事务和唯一约束；内存实现只用于本地开发和单元测试。
 */
public interface AgentStageRunStore {

    /**
     * 保存阶段运行快照。
     *
     * @param stageRun 阶段运行快照
     * @return 保存后的快照
     */
    AgentStageRun save(AgentStageRun stageRun);

    /**
     * 按阶段运行 ID 查询。
     *
     * @param stageRunId 阶段运行 ID
     * @return 阶段运行
     */
    Optional<AgentStageRun> findById(String stageRunId);

    /**
     * 查询某个 RD 任务的全部阶段。
     *
     * @param taskId RD 任务 ID
     * @return 阶段运行列表
     */
    List<AgentStageRun> listByTask(String taskId);

    /**
     * Queries stage runs for multiple tasks.
     *
     * <p>Persistent adapters should override this method with a batched query. The default keeps
     * in-memory and small test implementations compatible while preserving a deterministic task
     * order.
     *
     * @param taskIds RD task IDs
     * @return stage runs for requested tasks
     */
    default List<AgentStageRun> listByTasks(List<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }
        return new LinkedHashSet<>(taskIds).stream()
                .filter(taskId -> taskId != null && !taskId.isBlank())
                .flatMap(taskId -> listByTask(taskId).stream())
                .toList();
    }

    /**
     * 推进阶段状态。
     *
     * @param stageRunId            阶段运行 ID
     * @param targetStatus          目标状态
     * @param errorCategory         错误分类
     * @param errorMessage          错误信息
     * @param updateTimeEpochMillis 更新时间
     * @return 新阶段运行快照
     */
    AgentStageRun transition(
            String stageRunId,
            AgentStageStatus targetStatus,
            String errorCategory,
            String errorMessage,
            long updateTimeEpochMillis
    );
}
