package com.wish.rd.engine.agent;

import java.util.List;
import java.util.Set;
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
     * 不可变产物写入：首次 INSERT；若 id 已存在则比对 stageRunId/artifactType/contentHash，
     * 相同则幂等返回，不同则抛 {@link IllegalStateException}。
     *
     * @param artifact 阶段产物
     * @return 保存后或已存在的阶段产物
     */
    AgentStageArtifact saveImmutable(AgentStageArtifact artifact);

    /**
     * 按任务查询阶段产物。
     *
     * @param taskId RD 任务 ID
     * @return 阶段产物列表
     */
    List<AgentStageArtifact> listByTask(String taskId);

    /**
     * Delete selected artifact types for one task. Storage adapters may override this for retention cleanup.
     */
    default int deleteByTaskAndTypes(String taskId, Set<String> artifactTypes) {
        return 0;
    }

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
            public AgentStageArtifact saveImmutable(AgentStageArtifact artifact) {
                return artifact;
            }

            @Override
            public List<AgentStageArtifact> listByTask(String taskId) {
                return List.of();
            }
        };
    }

    /**
     * 校验不可变产物是否与已存记录一致。
     */
    static void assertImmutableCompatible(AgentStageArtifact existing, AgentStageArtifact incoming) {
        if (existing.stageRunId().equals(incoming.stageRunId())
                && existing.artifactType().equals(incoming.artifactType())
                && existing.contentHash().equals(incoming.contentHash())) {
            return;
        }
        throw new IllegalStateException("immutable artifact conflict: " + incoming.artifactId());
    }
}
