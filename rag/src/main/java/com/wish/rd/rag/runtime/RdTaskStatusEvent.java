package com.wish.rd.rag.runtime;

/**
 * RD 任务状态事件：任务进入某状态（或触发某管理动作）时落库的审计记录。
 *
 * <p>{@code status} 取 {@link RdTaskStatus} 枚举名，或管理动作名
 * {@code PAUSED} / {@code RESUMED} / {@code DELETED}（这些不是状态机节点，
 * 不进 {@link RdTaskStatus}）。{@code durationMillis} 表示相对上一事件进入时刻
 * 的耗时，首条事件为 0。
 *
 * @param id                   Snowflake 字符串事件 ID
 * @param taskId               所属任务 ID
 * @param status               状态名或动作名
 * @param title                展示标题（沿用任务当时标题）
 * @param message              说明 / 错误信息
 * @param enteredAtEpochMillis 进入该状态的时刻
 * @param durationMillis       本步耗时（相对上一事件）
 * @param trigger              触发来源
 */
public record RdTaskStatusEvent(
        String id,
        String taskId,
        String status,
        String title,
        String message,
        long enteredAtEpochMillis,
        long durationMillis,
        String trigger
) {

    public static final String ACTION_PAUSED = "PAUSED";
    public static final String ACTION_RESUMED = "RESUMED";
    public static final String ACTION_DELETED = "DELETED";

    public RdTaskStatusEvent {
        id = id == null ? "" : id.strip();
        taskId = taskId == null ? "" : taskId.strip();
        status = status == null ? "" : status.strip();
        title = title == null ? "" : title;
        message = message == null ? "" : message;
        enteredAtEpochMillis = Math.max(0L, enteredAtEpochMillis);
        durationMillis = Math.max(0L, durationMillis);
        trigger = trigger == null || trigger.isBlank() ? RdTaskEventTrigger.SYSTEM.name() : trigger.strip().toUpperCase();
    }
}
