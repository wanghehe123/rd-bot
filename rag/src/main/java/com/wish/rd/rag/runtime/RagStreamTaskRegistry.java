package com.wish.rd.rag.runtime;

import java.util.LinkedHashMap;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Component;

/**
 * 流式聊天任务注册表：跟踪 /rag/v3/chat 的任务生命周期状态。
 *
 * <p>任务状态机：RUNNING（运行中）→ DONE（完成）/ REJECTED（限流拒绝）/ CANCELLED（被 stop）。
 * 已取消的任务对后续的 complete/registerRunning 操作是"终态"，不会被覆盖，
 * 保证 stop 后即使流程继续也不会误标为完成。
 *
 * <p>供 /rag/v3/stop、/rag/v3/tasks/{taskId} 以及引擎内部的限流拒绝分支共同使用。
 */
@Component
public final class RagStreamTaskRegistry {

    /** 任务集合，taskId → 任务快照。 */
    private final LinkedHashMap<String, RagStreamTask> tasks = new LinkedHashMap<>();

    public static RagStreamTaskRegistry inMemory() {
        return new RagStreamTaskRegistry();
    }

    /**
     * 注册任务为 RUNNING。若任务已被 CANCELLED 则保持终态不变。
     *
     * @return 注册后的任务快照
     */
    public synchronized RagStreamTask registerRunning(String taskId, String conversationId) {
        long now = System.currentTimeMillis();
        RagStreamTask existing = tasks.get(taskId);
        // 已取消的任务视为终态，不再转为 RUNNING
        if (existing != null && "CANCELLED".equals(existing.status())) {
            return existing;
        }
        RagStreamTask task = new RagStreamTask(
                requireTaskId(taskId),
                safe(conversationId),
                "RUNNING",
                "",
                "",
                "",
                existing == null ? now : existing.createTimeEpochMillis(),
                now
        );
        tasks.put(task.taskId(), task);
        return task;
    }

    /**
     * 标记任务完成（DONE）。已取消的任务保持终态。
     */
    public synchronized RagStreamTask complete(String taskId, String messageId, String title) {
        RagStreamTask existing = get(taskId);
        if ("CANCELLED".equals(existing.status())) {
            return existing;
        }
        RagStreamTask completed = new RagStreamTask(
                existing.taskId(),
                existing.conversationId(),
                "DONE",
                safe(messageId),
                safe(title),
                "",
                existing.createTimeEpochMillis(),
                System.currentTimeMillis()
        );
        tasks.put(completed.taskId(), completed);
        return completed;
    }

    /**
     * 取消任务（CANCELLED）。即使任务此前不存在也会创建一个已取消记录，
     * 保证 stop 接口幂等。
     */
    public synchronized RagStreamTask cancel(String taskId) {
        long now = System.currentTimeMillis();
        String safeTaskId = requireTaskId(taskId);
        RagStreamTask existing = tasks.get(safeTaskId);
        RagStreamTask cancelled = new RagStreamTask(
                safeTaskId,
                existing == null ? "" : existing.conversationId(),
                "CANCELLED",
                existing == null ? "" : existing.messageId(),
                existing == null ? "" : existing.title(),
                existing == null ? "" : existing.errorMessage(),
                existing == null ? now : existing.createTimeEpochMillis(),
                now
        );
        tasks.put(safeTaskId, cancelled);
        return cancelled;
    }

    /**
     * 标记任务被限流拒绝（REJECTED），携带拒绝原因。
     */
    public synchronized RagStreamTask reject(String taskId, String conversationId, String messageId, String errorMessage) {
        long now = System.currentTimeMillis();
        String safeTaskId = requireTaskId(taskId);
        RagStreamTask existing = tasks.get(safeTaskId);
        RagStreamTask rejected = new RagStreamTask(
                safeTaskId,
                safe(conversationId),
                "REJECTED",
                safe(messageId),
                "",
                safe(errorMessage),
                existing == null ? now : existing.createTimeEpochMillis(),
                now
        );
        tasks.put(safeTaskId, rejected);
        return rejected;
    }

    /** 按 ID 查询任务，不存在抛异常。 */
    public synchronized RagStreamTask get(String taskId) {
        String safeTaskId = requireTaskId(taskId);
        RagStreamTask task = tasks.get(safeTaskId);
        if (task == null) {
            throw new NoSuchElementException("rag stream task not found: " + safeTaskId);
        }
        return task;
    }

    /** 校验 taskId 非空并去空白。 */
    private String requireTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        return taskId.strip();
    }

    /** null 安全化：null 转空串。 */
    private String safe(String value) {
        return value == null ? "" : value;
    }
}
