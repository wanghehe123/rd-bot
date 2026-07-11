package com.wish.rd.engine.admin.trace;

/** Filters for task-rooted execution traces. */
public record ExecutionTraceQuery(
        String projectId,
        String taskType,
        String status,
        String role,
        String provider,
        String keyword,
        int page,
        int pageSize
) {
    public ExecutionTraceQuery {
        projectId = safe(projectId);
        taskType = safe(taskType).toUpperCase(java.util.Locale.ROOT);
        status = safe(status).toUpperCase(java.util.Locale.ROOT);
        role = safe(role).toUpperCase(java.util.Locale.ROOT);
        provider = safe(provider);
        keyword = safe(keyword);
        page = Math.max(1, page);
        pageSize = pageSize <= 0 ? 20 : Math.min(pageSize, 100);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
