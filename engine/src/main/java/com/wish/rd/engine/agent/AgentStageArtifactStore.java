package com.wish.rd.engine.agent;

import java.util.List;
import com.wish.rd.engine.agent.model.AgentStageArtifact;

/**
 * Agent 阶段产物存储端口。
 */
public interface AgentStageArtifactStore {

    /**
     * 保存阶段产物。
     *
     * @param artifact 阶段产物
     * @return 保存后的阶段产物
     */
    AgentStageArtifact save(AgentStageArtifact artifact);

    /**
     * 按任务查询阶段产物。
     *
     * @param taskId RD 任务 ID
     * @return 阶段产物列表
     */
    List<AgentStageArtifact> listByTask(String taskId);

    /**
     * 返回默认空存储。
     *
     * @return 空实现
     */
    static AgentStageArtifactStore noop() {
        return new AgentStageArtifactStore() {
            @Override
            public AgentStageArtifact save(AgentStageArtifact artifact) {
                return artifact;
            }

            @Override
            public List<AgentStageArtifact> listByTask(String taskId) {
                return List.of();
            }
        };
    }
}
