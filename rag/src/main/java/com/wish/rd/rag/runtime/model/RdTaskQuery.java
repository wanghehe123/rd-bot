package com.wish.rd.rag.runtime.model;

import java.util.Optional;

/**
 * RD 任务管理台查询参数。
 *
 * <p>所有过滤字段均可空（表示不过滤）；{@code page} 从 1 开始。
 *
 * @param taskType 任务类型过滤（BUG_FIX/REQUIREMENT/QNA），空表示不过滤
 * @param status   状态过滤（{@link RdTaskStatus} 名），空表示不过滤
 * @param priority 优先级过滤（P0/P1/P2），空表示不过滤
 * @param ticketId 工单 ID 子串
 * @param keyword  关键词（匹配标题/工单标题/工单 ID）
 * @param page     页码，从 1 开始
 * @param pageSize 每页大小
 */
public record RdTaskQuery(
        String taskType,
        String status,
        String priority,
        String ticketId,
        String keyword,
        int page,
        int pageSize
) {

    public RdTaskQuery {
        taskType = taskType == null ? null : taskType.strip();
        page = Math.max(1, page);
        pageSize = pageSize <= 0 ? 20 : pageSize;
    }

    /**
     * 兼容旧调用方：默认不过滤任务类型。
     */
    public RdTaskQuery(String status, String priority, String ticketId, String keyword, int page, int pageSize) {
        this(null, status, priority, ticketId, keyword, page, pageSize);
    }

    /** 是否要求任务类型等于给定值。 */
    public boolean matchesTaskType(String candidate) {
        return taskType == null || taskType.isBlank() || taskType.equalsIgnoreCase(candidate);
    }

    /** 是否要求状态等于给定值。 */
    public boolean matchesStatus(RdTaskStatus candidate) {
        return status == null || status.isBlank() || candidate.name().equalsIgnoreCase(status.strip());
    }

    /** 是否要求优先级匹配。 */
    public boolean matchesPriority(String candidate) {
        if (priority == null || priority.isBlank()) {
            return true;
        }
        return priority.strip().equalsIgnoreCase(candidate);
    }

    /** 是否要求工单 ID / 关键词命中（关键词命中标题、工单标题、工单 ID 任一即可）。 */
    public boolean matchesKeywords(String ticketIdValue, String ticketTitle, String title) {
        boolean ticketOk = ticketId == null || ticketId.isBlank()
                || (ticketIdValue != null && ticketIdValue.toLowerCase().contains(ticketId.strip().toLowerCase()));
        if (!ticketOk) {
            return false;
        }
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String needle = keyword.strip().toLowerCase();
        return containsLower(ticketIdValue, needle)
                || containsLower(ticketTitle, needle)
                || containsLower(title, needle);
    }

    private static boolean containsLower(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle);
    }

    /** 状态过滤的 {@link Optional} 形式，便于内部判空。 */
    public Optional<String> statusOptional() {
        return status == null || status.isBlank() ? Optional.empty() : Optional.of(status.strip());
    }
}
