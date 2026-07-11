package com.wish.rd.engine.agent;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 根据任务类型和阶段运行快照计算当前交付进度。
 *
 * <p>供任务执行概览、Dashboard 和执行追踪复用，避免各个读模型对角色顺序、重试选择和进度
 * 口径出现偏差。
 */
public final class AgentStageProgressCalculator {

    private static final int STEPS_PER_ROLE = 6;
    private static final Comparator<AgentStageRun> ATTEMPT_RECENCY = Comparator
            .comparingInt(AgentStageRun::attemptNo)
            .thenComparingLong(AgentStageRun::createTimeEpochMillis)
            .thenComparing(AgentStageRun::stageRunId);

    /**
     * 计算任务的最新阶段、进度和重试次数。
     *
     * @param taskType 任务类型（BUG_FIX 使用 Bug 角色序列，其余使用需求角色序列）
     * @param stageRuns 某一任务的全部阶段运行记录
     * @return 可供管理读模型直接消费的进度快照
     */
    public AgentStageProgress calculate(String taskType, List<AgentStageRun> stageRuns) {
        List<AgentRole> roleOrder = roleOrder(taskType);
        Map<AgentRole, AgentStageRun> latestByRole = latestByRole(roleOrder, stageRuns);
        AgentStageRun current = currentRun(roleOrder, latestByRole);
        int completedSteps = roleOrder.stream()
                .map(latestByRole::get)
                .mapToInt(AgentStageProgressCalculator::progressSteps)
                .sum();
        int retryCount = latestByRole.values().stream()
                .mapToInt(run -> Math.max(0, run.attemptNo() - 1))
                .sum();
        return new AgentStageProgress(
                current == null ? "" : current.role().name(),
                current == null ? "" : current.status().name(),
                Math.min(completedSteps, roleOrder.size() * STEPS_PER_ROLE),
                roleOrder.size() * STEPS_PER_ROLE,
                current == null ? "" : current.providerName(),
                retryCount,
                Map.copyOf(latestByRole)
        );
    }

    private static Map<AgentRole, AgentStageRun> latestByRole(
            List<AgentRole> roleOrder,
            List<AgentStageRun> stageRuns
    ) {
        Map<AgentRole, AgentStageRun> latest = new LinkedHashMap<>();
        List<AgentStageRun> safeRuns = stageRuns == null ? List.of() : stageRuns;
        for (AgentStageRun run : safeRuns) {
            if (run == null || !roleOrder.contains(run.role())) {
                continue;
            }
            latest.merge(run.role(), run, (left, right) -> ATTEMPT_RECENCY.compare(left, right) >= 0 ? left : right);
        }
        return latest;
    }

    private static AgentStageRun currentRun(List<AgentRole> roleOrder, Map<AgentRole, AgentStageRun> latestByRole) {
        for (AgentRole role : roleOrder) {
            AgentStageRun run = latestByRole.get(role);
            if (run != null && run.status() != AgentStageStatus.SUCCEEDED) {
                return run;
            }
        }
        return latestByRole.values().stream().max(ATTEMPT_RECENCY).orElse(null);
    }

    private static int progressSteps(AgentStageRun run) {
        if (run == null || run.status() == null) {
            return 0;
        }
        return switch (run.status()) {
            case PENDING -> 0;
            case CONTEXT_READY -> 1;
            case DISPATCHING, RECOVERING -> 2;
            case RUNNING -> 3;
            case RESULT_COLLECTING -> 4;
            case VERIFYING -> 5;
            case SUCCEEDED, FAILED_RETRYABLE, FAILED_NEEDS_HUMAN, SKIPPED, CANCELLED -> STEPS_PER_ROLE;
        };
    }

    private static List<AgentRole> roleOrder(String taskType) {
        return "BUG_FIX".equalsIgnoreCase(taskType)
                ? AgentRole.bugFixOrder()
                : AgentRole.requirementDeliveryOrder();
    }

    /**
     * 任务阶段进度的不可变读模型。
     *
     * @param currentRole 当前角色，尚未创建阶段时为空
     * @param currentStatus 当前角色的阶段状态，尚未创建阶段时为空
     * @param completedSteps 已完成的细粒度步骤数
     * @param totalSteps 阶段总步骤数
     * @param provider 当前角色使用的 provider，未知时为空
     * @param retryCount 所有最新角色阶段累计的重试次数
     * @param latestByRole 每个有效角色的最新 attempt
     */
    public record AgentStageProgress(
            String currentRole,
            String currentStatus,
            int completedSteps,
            int totalSteps,
            String provider,
            int retryCount,
            Map<AgentRole, AgentStageRun> latestByRole
    ) {
        public AgentStageProgress {
            currentRole = currentRole == null ? "" : currentRole;
            currentStatus = currentStatus == null ? "" : currentStatus;
            provider = provider == null ? "" : provider;
            completedSteps = Math.max(0, completedSteps);
            totalSteps = Math.max(0, totalSteps);
            retryCount = Math.max(0, retryCount);
            latestByRole = latestByRole == null ? Map.of() : Map.copyOf(latestByRole);
        }
    }
}
