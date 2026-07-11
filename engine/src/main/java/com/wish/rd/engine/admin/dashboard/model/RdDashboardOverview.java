package com.wish.rd.engine.admin.dashboard.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 项目交付驾驶舱的只读聚合结果。
 *
 * <p>由 {@code /admin/dashboard/overview} 返回，所有任务状态口径均在 engine 聚合，前端
 * 不得根据分页任务结果自行推断总数。
 */
public record RdDashboardOverview(
        String projectId,
        String projectName,
        long requirementCount,
        long bugFixCount,
        long inProgressCount,
        long waitingHumanCount,
        long completedCount,
        long blockedCount,
        Map<String, Long> statusCounts,
        AvailabilityRatio successRate,
        RuntimeSnapshot runtime,
        List<TaskSummary> currentExecutions,
        List<TaskSummary> recentDeliveries,
        KnowledgeSupport knowledgeSupport,
        long generatedAtEpochMillis
) {
    public RdDashboardOverview {
        projectId = projectId == null ? "" : projectId;
        projectName = projectName == null ? "" : projectName;
        statusCounts = statusCounts == null ? Map.of() : Map.copyOf(statusCounts);
        successRate = successRate == null ? AvailabilityRatio.unavailable() : successRate;
        runtime = runtime == null ? RuntimeSnapshot.unavailable() : runtime;
        currentExecutions = currentExecutions == null ? List.of() : List.copyOf(currentExecutions);
        recentDeliveries = recentDeliveries == null ? List.of() : List.copyOf(recentDeliveries);
        knowledgeSupport = knowledgeSupport == null ? KnowledgeSupport.unavailable() : knowledgeSupport;
        generatedAtEpochMillis = Math.max(0L, generatedAtEpochMillis);
    }

    /** 可用性明确的百分比值，避免将未知数据伪装为零。 */
    public record AvailabilityRatio(boolean available, BigDecimal value) {
        public AvailabilityRatio {
            value = available && value != null ? value : null;
        }

        /** 返回明确不可用的比例。 */
        public static AvailabilityRatio unavailable() {
            return new AvailabilityRatio(false, null);
        }
    }

    /** 运行态数据块。 */
    public record RuntimeSnapshot(
            boolean available,
            long activeAlertCount,
            long runningExecutionCount,
            BigDecimal estimatedSpendCny,
            boolean costAvailable
    ) {
        public RuntimeSnapshot {
            activeAlertCount = Math.max(0L, activeAlertCount);
            runningExecutionCount = Math.max(0L, runningExecutionCount);
            estimatedSpendCny = estimatedSpendCny == null ? BigDecimal.ZERO : estimatedSpendCny;
        }

        /** 返回可观测运行态不可用的快照。 */
        public static RuntimeSnapshot unavailable() {
            return new RuntimeSnapshot(false, 0L, 0L, BigDecimal.ZERO, false);
        }
    }

    /** Dashboard 表格使用的任务摘要。 */
    public record TaskSummary(
            String taskId,
            String projectId,
            String taskType,
            String status,
            String title,
            long updateTimeEpochMillis,
            String currentRole,
            String currentStageStatus,
            int progressCompleted,
            int progressTotal,
            String provider,
            int retryCount,
            long elapsedMillis,
            boolean running
    ) {
        public TaskSummary {
            taskId = safe(taskId);
            projectId = safe(projectId);
            taskType = safe(taskType);
            status = safe(status);
            title = safe(title);
            currentRole = safe(currentRole);
            currentStageStatus = safe(currentStageStatus);
            provider = safe(provider);
            updateTimeEpochMillis = Math.max(0L, updateTimeEpochMillis);
            progressCompleted = Math.max(0, progressCompleted);
            progressTotal = Math.max(0, progressTotal);
            retryCount = Math.max(0, retryCount);
            elapsedMillis = Math.max(0L, elapsedMillis);
        }
    }

    /** 当前项目绑定知识库的支撑资源摘要。 */
    public record KnowledgeSupport(
            boolean available,
            String knowledgeBaseId,
            String knowledgeBaseName,
            long documentCount,
            long enabledDocumentCount
    ) {
        public KnowledgeSupport {
            knowledgeBaseId = safe(knowledgeBaseId);
            knowledgeBaseName = safe(knowledgeBaseName);
            documentCount = Math.max(0L, documentCount);
            enabledDocumentCount = Math.max(0L, enabledDocumentCount);
        }

        /** 返回没有可解析项目知识库的支撑资源快照。 */
        public static KnowledgeSupport unavailable() {
            return new KnowledgeSupport(false, "", "", 0L, 0L);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
