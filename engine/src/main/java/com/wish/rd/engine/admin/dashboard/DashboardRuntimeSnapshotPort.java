package com.wish.rd.engine.admin.dashboard;

import com.wish.rd.engine.admin.dashboard.model.RdDashboardOverview;

import java.util.List;
import java.util.Map;

/**
 * Dashboard 查询运行时告警、容器和阶段进度的端口。
 *
 * <p>接口位于 engine，Docker、预算和告警基础设施适配由 bootstrap 实现，避免读模型直接
 * 依赖外部 SDK 或执行器实现。
 */
@FunctionalInterface
public interface DashboardRuntimeSnapshotPort {

    /**
     * 查询当前筛选任务集合的运行态快照。
     *
     * @param projectId 已选择项目 ID，全部项目范围时为空
     * @param taskIds 已过滤的任务 ID
     * @return 聚合运行态与每个任务的进度
     */
    Snapshot snapshot(String projectId, List<String> taskIds);

    /** 返回不伪造零值的不可用端口。 */
    static DashboardRuntimeSnapshotPort unavailable() {
        return (projectId, taskIds) -> Snapshot.unavailable();
    }

    /** Dashboard 运行态端口的返回值。 */
    record Snapshot(
            RdDashboardOverview.RuntimeSnapshot runtime,
            Map<String, TaskRuntime> taskRuntimes
    ) {
        public Snapshot {
            runtime = runtime == null ? RdDashboardOverview.RuntimeSnapshot.unavailable() : runtime;
            taskRuntimes = taskRuntimes == null ? Map.of() : Map.copyOf(taskRuntimes);
        }

        /** 返回没有运行时提供者的快照。 */
        public static Snapshot unavailable() {
            return new Snapshot(RdDashboardOverview.RuntimeSnapshot.unavailable(), Map.of());
        }
    }

    /** 单个任务在 Dashboard 中需要的阶段与容器状态。 */
    record TaskRuntime(
            String currentRole,
            String currentStageStatus,
            int progressCompleted,
            int progressTotal,
            String provider,
            int retryCount,
            long elapsedMillis,
            boolean running
    ) {
        public TaskRuntime {
            currentRole = currentRole == null ? "" : currentRole.strip();
            currentStageStatus = currentStageStatus == null ? "" : currentStageStatus.strip();
            provider = provider == null ? "" : provider.strip();
            progressCompleted = Math.max(0, progressCompleted);
            progressTotal = Math.max(0, progressTotal);
            retryCount = Math.max(0, retryCount);
            elapsedMillis = Math.max(0L, elapsedMillis);
        }

        /** 返回尚未取得阶段运行记录的任务状态。 */
        public static TaskRuntime empty() {
            return new TaskRuntime("", "", 0, 0, "", 0, 0L, false);
        }
    }
}
