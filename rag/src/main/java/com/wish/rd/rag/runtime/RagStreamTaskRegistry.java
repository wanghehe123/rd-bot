package com.wish.rd.rag.runtime;

import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.framework.id.SnowflakeIdGenerator;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Component;

/**
 * RD 任务状态机注册表。
 *
 * <p>供 /rag/v3/tasks/{taskId}、/rag/v3/stop、RAG 编排和 RD 修复全流程编排共同推进任务状态。
 * 任务快照通过 {@link RdTaskStore} 持久化，默认内存实现用于本地和单测，PostgreSQL 实现由 bootstrap 提供。
 */
@Component
public final class RagStreamTaskRegistry {

    private final RdTaskStore taskStore;
    private final SnowflakeIdGenerator idGenerator;

    public RagStreamTaskRegistry(RdTaskStore taskStore, SnowflakeIdGenerator idGenerator) {
        this.taskStore = taskStore == null ? new InMemoryRdTaskStore() : taskStore;
        this.idGenerator = idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator;
    }

    /**
     * 创建内存任务注册表。
     *
     * @return 内存任务注册表
     */
    public static RagStreamTaskRegistry inMemory() {
        return new RagStreamTaskRegistry(new InMemoryRdTaskStore(), SnowflakeIdGenerator.defaultGenerator());
    }

    /**
     * 创建 Bug 修复任务，初始状态为 CREATED。
     *
     * @param ticket   工单快照
     * @param priority 优先级
     * @return 新任务快照
     */
    public synchronized RdBugFixTask createBugFixTask(TicketSnapshot ticket, String priority) {
        TicketSnapshot safeTicket = normalizeTicket(ticket);
        RdBugFixTask task = RdBugFixTask.created(
                idGenerator.nextIdString(),
                safeTicket.ticketId(),
                safeTicket.title(),
                priority,
                System.currentTimeMillis()
        );
        return taskStore.saveBugFixTask(task);
    }

    /**
     * 将任务推进到 SEARCHING。
     *
     * @param taskId  任务 ID
     * @param summary 检索摘要或标题
     * @return 新任务快照
     */
    public synchronized RdBugFixTask markSearching(String taskId, String summary) {
        RdBugFixTask existing = get(taskId);
        return save(transition(existing, RdTaskStatus.SEARCHING, "", summary, "", "", "", ""));
    }

    /**
     * 将任务推进到 EXECUTING。
     *
     * @param taskId         任务 ID
     * @param promptSnapshot Prompt 快照
     * @return 新任务快照
     */
    public synchronized RdBugFixTask markExecuting(String taskId, String promptSnapshot) {
        RdBugFixTask existing = get(taskId);
        return save(transition(existing, RdTaskStatus.EXECUTING, "", "", promptSnapshot, "", "", ""));
    }

    /**
     * 将任务推进到 COMMITTED。
     *
     * @param taskId              任务 ID
     * @param pullRequestUrl      PR 链接
     * @param executionResultJson 执行结果 JSON
     * @return 新任务快照
     */
    public synchronized RdBugFixTask markCommitted(
            String taskId,
            String pullRequestUrl,
            String executionResultJson
    ) {
        RdBugFixTask existing = get(taskId);
        return save(transition(
                existing,
                RdTaskStatus.COMMITTED,
                "",
                "",
                "",
                executionResultJson,
                pullRequestUrl,
                ""
        ));
    }

    /**
     * 将任务推进到 MERGED。
     *
     * @param taskId 任务 ID
     * @return 新任务快照
     */
    public synchronized RdBugFixTask markMerged(String taskId) {
        RdBugFixTask existing = get(taskId);
        return save(transition(existing, RdTaskStatus.MERGED, "", "", "", "", "", ""));
    }

    /**
     * 将任务推进到 REJECTED。
     *
     * @param taskId       任务 ID
     * @param errorMessage 错误或 RD 打回原因
     * @return 新任务快照
     */
    public synchronized RdBugFixTask markRejected(String taskId, String errorMessage) {
        RdBugFixTask existing = get(taskId);
        return save(transition(existing, RdTaskStatus.REJECTED, "", "", "", "", "", errorMessage));
    }

    /**
     * 兼容旧 RAG 流：注册任务进入 SEARCHING。
     *
     * @param taskId         任务 ID
     * @param conversationId 旧会话 ID，当前任务域不再使用
     * @return 新任务快照
     */
    public synchronized RdBugFixTask registerRunning(String taskId, String conversationId) {
        RdBugFixTask existing = taskStore.findBugFixTask(requireTaskId(taskId))
                .orElseGet(() -> save(newTaskWithId(taskId)));
        if (existing.status() == RdTaskStatus.REJECTED || existing.status() == RdTaskStatus.MERGED) {
            return existing;
        }
        return save(transition(existing, RdTaskStatus.SEARCHING, "", "", "", "", "", ""));
    }

    /**
     * 兼容旧 RAG 流：将任务标记为 COMMITTED。
     *
     * @param taskId    任务 ID
     * @param messageId 旧消息 ID
     * @param title     标题
     * @return 新任务快照
     */
    public synchronized RdBugFixTask complete(String taskId, String messageId, String title) {
        RdBugFixTask existing = get(taskId);
        if (existing.status() == RdTaskStatus.REJECTED || existing.status() == RdTaskStatus.MERGED) {
            return existing;
        }
        RdBugFixTask executing = existing.status() == RdTaskStatus.SEARCHING
                ? transition(existing, RdTaskStatus.EXECUTING, "", "", "", "", "", "")
                : existing;
        return save(transition(executing, RdTaskStatus.COMMITTED, messageId, title, "", "", "", ""));
    }

    /**
     * 兼容 stop 接口：将任务标记为 REJECTED。
     *
     * @param taskId 任务 ID
     * @return 新任务快照
     */
    public synchronized RdBugFixTask cancel(String taskId) {
        String safeTaskId = requireTaskId(taskId);
        RdBugFixTask existing = taskStore.findBugFixTask(safeTaskId)
                .orElseGet(() -> save(newTaskWithId(safeTaskId)));
        return save(transition(existing, RdTaskStatus.REJECTED, "", "", "", "", "", "任务已停止"));
    }

    /**
     * 兼容限流拒绝分支：将任务标记为 REJECTED。
     *
     * @param taskId         任务 ID
     * @param conversationId 旧会话 ID，当前任务域不再使用
     * @param messageId      旧消息 ID
     * @param errorMessage   拒绝原因
     * @return 新任务快照
     */
    public synchronized RdBugFixTask reject(
            String taskId,
            String conversationId,
            String messageId,
            String errorMessage
    ) {
        String safeTaskId = requireTaskId(taskId);
        RdBugFixTask existing = taskStore.findBugFixTask(safeTaskId)
                .orElseGet(() -> save(newTaskWithId(safeTaskId)));
        return save(transition(existing, RdTaskStatus.REJECTED, messageId, "", "", "", "", errorMessage));
    }

    /**
     * 按 ID 查询 Bug 修复任务。
     *
     * @param taskId 任务 ID
     * @return 任务快照
     */
    public synchronized RdBugFixTask get(String taskId) {
        String safeTaskId = requireTaskId(taskId);
        return taskStore.findBugFixTask(safeTaskId)
                .orElseThrow(() -> new NoSuchElementException("rd task not found: " + safeTaskId));
    }

    /**
     * 查询所有 Bug 修复任务。
     *
     * @return 任务快照列表
     */
    public synchronized List<RdBugFixTask> listBugFixTasks() {
        return taskStore.listBugFixTasks();
    }

    private RdBugFixTask transition(
            RdBugFixTask existing,
            RdTaskStatus targetStatus,
            String messageId,
            String title,
            String promptSnapshot,
            String executionResultJson,
            String pullRequestUrl,
            String errorMessage
    ) {
        ensureTransition(existing.status(), targetStatus);
        return existing.withState(
                targetStatus,
                messageId,
                title,
                promptSnapshot,
                executionResultJson,
                pullRequestUrl,
                errorMessage,
                System.currentTimeMillis()
        );
    }

    private void ensureTransition(RdTaskStatus source, RdTaskStatus target) {
        if (source == target) {
            return;
        }
        boolean legal = switch (source) {
            case CREATED -> target == RdTaskStatus.SEARCHING || target == RdTaskStatus.REJECTED;
            case SEARCHING -> target == RdTaskStatus.EXECUTING || target == RdTaskStatus.REJECTED;
            case EXECUTING -> target == RdTaskStatus.COMMITTED || target == RdTaskStatus.REJECTED;
            case COMMITTED -> target == RdTaskStatus.MERGED || target == RdTaskStatus.REJECTED;
            case REJECTED -> target == RdTaskStatus.EXECUTING;
            case MERGED -> false;
        };
        if (!legal) {
            throw new IllegalStateException("illegal task status transition: " + source + " -> " + target);
        }
    }

    private RdBugFixTask save(RdBugFixTask task) {
        return taskStore.saveBugFixTask(task);
    }

    private RdBugFixTask newTaskWithId(String taskId) {
        long now = System.currentTimeMillis();
        return RdBugFixTask.created(taskId, "", "", "P2", now);
    }

    private TicketSnapshot normalizeTicket(TicketSnapshot ticket) {
        if (ticket != null) {
            return ticket;
        }
        return new TicketSnapshot(idGenerator.nextIdString(), "", "", List.of(), Instant.now());
    }

    private String requireTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        return taskId.strip();
    }
}
