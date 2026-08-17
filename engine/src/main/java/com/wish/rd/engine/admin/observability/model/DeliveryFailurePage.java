package com.wish.rd.engine.admin.observability.model;

import java.util.List;

/**
 * Paginated folded failure categories for the selected window.
 *
 * @param projectId blank when all projects
 * @param window canonical window
 * @param generatedAtEpochMillis response time
 * @param page 1-based page
 * @param pageSize page size
 * @param total matching categories
 * @param items page rows
 * @param dataQuality freshness
 */
public record DeliveryFailurePage(
        String projectId,
        String window,
        long generatedAtEpochMillis,
        int page,
        int pageSize,
        long total,
        List<FailureRow> items,
        List<DataQualityStatus> dataQuality
) {

    public DeliveryFailurePage {
        projectId = projectId == null ? "" : projectId;
        window = window == null ? DeliveryObservabilityWindow.ONE_DAY.token() : window;
        items = items == null ? List.of() : List.copyOf(items);
        dataQuality = dataQuality == null ? List.of() : List.copyOf(dataQuality);
        page = Math.max(1, page);
        pageSize = Math.max(1, pageSize);
        total = Math.max(0L, total);
    }

    /**
     * One folded failure category.
     *
     * @param category allowlisted category
     * @param count tasks
     * @param share of failed terminal tasks, or {@code NaN} when no sample
     */
    public record FailureRow(String category, long count, double share) {
        public FailureRow {
            category = category == null || category.isBlank() ? "UNKNOWN" : category.strip();
            count = Math.max(0L, count);
        }
    }
}
