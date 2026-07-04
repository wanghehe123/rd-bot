package com.wish.rd.engine.agent;

import java.util.List;

/**
 * 多 Agent 工作流经验存储端口。
 */
public interface WorkflowExperienceStore {

    /**
     * 保存经验条目。
     *
     * @param entry 经验条目
     * @return 保存后的条目
     */
    WorkflowExperienceEntry save(WorkflowExperienceEntry entry);

    /**
     * 按任务查询经验条目。
     *
     * @param taskId RD 任务 ID
     * @return 经验条目列表
     */
    List<WorkflowExperienceEntry> listByTask(String taskId);

    /**
     * 检索可复用经验。
     *
     * @param query         检索文本
     * @param excludeTaskId 需要排除的当前任务 ID
     * @param limit         最大返回数量
     * @return 可复用经验列表
     */
    default List<WorkflowExperienceEntry> searchReusable(String query, String excludeTaskId, int limit) {
        return List.of();
    }

    /**
     * 返回默认空存储。
     *
     * @return 空实现
     */
    static WorkflowExperienceStore noop() {
        return new WorkflowExperienceStore() {
            @Override
            public WorkflowExperienceEntry save(WorkflowExperienceEntry entry) {
                return entry;
            }

            @Override
            public List<WorkflowExperienceEntry> listByTask(String taskId) {
                return List.of();
            }
        };
    }
}
