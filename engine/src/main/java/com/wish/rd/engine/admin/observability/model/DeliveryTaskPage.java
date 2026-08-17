package com.wish.rd.engine.admin.observability.model;

import java.util.List;

/**
 * Paginated task breakdown for drill-down. Rows never include prompts or raw events.
 *
 * @param projectId blank when all projects
 * @param window canonical window
 * @param generatedAtEpochMillis response time
 * @param page 1-based page
 * @param pageSize page size
 * @param total matching tasks
 * @param items page rows
 * @param dataQuality freshness
 */
public record DeliveryTaskPage(
        String projectId,
        String window,
        long generatedAtEpochMillis,
        int page,
        int pageSize,
        long total,
        List<DeliveryTaskRow> items,
        List<DataQualityStatus> dataQuality
) {

    public DeliveryTaskPage {
        projectId = projectId == null ? "" : projectId;
        window = window == null ? DeliveryObservabilityWindow.ONE_DAY.token() : window;
        items = items == null ? List.of() : List.copyOf(items);
        dataQuality = dataQuality == null ? List.of() : List.copyOf(dataQuality);
        page = Math.max(1, page);
        pageSize = Math.max(1, pageSize);
        total = Math.max(0L, total);
    }

    /**
     * One task summary for observability drill-down.
     *
     * @param taskId task id
     * @param title operator title
     * @param projectId project id
     * @param status task status
     * @param role latest finished or running role
     * @param durationSeconds accepted-to-terminal seconds, or {@code NaN}
     * @param failureCategory folded category or blank
     * @param tracePath existing execution-trace path
     * @param taskPath existing task-detail path
     */
    public record DeliveryTaskRow(
            String taskId,
            String title,
            String projectId,
            String status,
            String role,
            double durationSeconds,
            String failureCategory,
            String tracePath,
            String taskPath
    ) {
        public DeliveryTaskRow {
            taskId = taskId == null ? "" : taskId;
            title = title == null ? "" : title;
            projectId = projectId == null ? "" : projectId;
            status = status == null ? "" : status;
            role = role == null ? "" : role;
            failureCategory = failureCategory == null ? "" : failureCategory;
            tracePath = tracePath == null || tracePath.isBlank()
                    ? "/admin/traces/" + taskId
                    : tracePath;
            taskPath = taskPath == null || taskPath.isBlank()
                    ? "/admin/rd-tasks/" + taskId
                    : taskPath;
        }
    }
}
