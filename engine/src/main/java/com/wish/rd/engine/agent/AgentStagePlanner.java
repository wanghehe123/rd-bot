package com.wish.rd.engine.agent;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;

/**
 * Agent 阶段规划器。
 *
 * <p>负责为需求交付工作流生成稳定的角色顺序和幂等键，不执行任何模型或外部系统调用。
 */
public final class AgentStagePlanner {

    private final Supplier<String> stageRunIdSupplier;

    /**
     * 创建阶段规划器。
     *
     * @param stageRunIdSupplier 阶段运行 ID 生成器
     */
    public AgentStagePlanner(Supplier<String> stageRunIdSupplier) {
        this.stageRunIdSupplier = Objects.requireNonNull(stageRunIdSupplier, "stageRunIdSupplier must not be null");
    }

    /**
     * 规划需求交付默认四阶段。
     *
     * @param taskId                RD 任务 ID
     * @param createTimeEpochMillis 创建时间
     * @return 阶段运行计划
     */
    public List<AgentStageRun> planRequirementDelivery(String taskId, long createTimeEpochMillis) {
        String safeTaskId = requireTaskId(taskId);
        return AgentRole.requirementDeliveryOrder().stream()
                .map(role -> AgentStageRun.pending(
                        nextStageRunId(),
                        safeTaskId,
                        role,
                        1,
                        idempotencyKey(safeTaskId, role, 1),
                        createTimeEpochMillis
                ))
                .toList();
    }

    private String nextStageRunId() {
        String stageRunId = stageRunIdSupplier.get();
        if (stageRunId == null || stageRunId.isBlank()) {
            throw new IllegalStateException("stageRunIdSupplier returned blank id");
        }
        return stageRunId.strip();
    }

    private static String idempotencyKey(String taskId, AgentRole role, int attemptNo) {
        return taskId + ":" + role.name() + ":" + attemptNo;
    }

    private static String requireTaskId(String taskId) {
        String safeTaskId = taskId == null ? "" : taskId.strip();
        if (safeTaskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        return safeTaskId;
    }
}
