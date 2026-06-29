package com.wish.rd.rag.runtime;

import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.framework.id.SnowflakeIdGenerator;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * RD 任务状态机注册表。
 *
 * <p>供 /rag/v3/tasks/{taskId}、/rag/v3/stop、RAG 编排和 RD 修复全流程编排共同推进任务状态。
 * 任务快照通过 {@link RdTaskStore} 持久化，默认内存实现用于本地和单测，PostgreSQL 实现由 bootstrap 提供。
 *
 * <p>每次状态推进与创建都会向 {@link RdTaskStatusEventStore} 追加一条状态事件，供管理台
 * 全链路时间线展示；管理台的暂停 / 恢复 / 修改 / 删除也经由本注册表执行，保证状态事件一致。
 */
@Component
public final class RagStreamTaskRegistry {

    private final RdTaskStore taskStore;
    private final RdTaskStatusEventStore eventStore;
    private final SnowflakeIdGenerator idGenerator;

    @Autowired
    public RagStreamTaskRegistry(
            RdTaskStore taskStore,
            RdTaskStatusEventStore eventStore,
            SnowflakeIdGenerator idGenerator
    ) {
        this.taskStore = taskStore == null ? new InMemoryRdTaskStore() : taskStore;
        this.eventStore = eventStore;
        this.idGenerator = idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator;
    }

    /**
     * 兼容旧调用方：不写状态事件（eventStore 为 null），仅维护任务快照。
     *
     * @param taskStore    任务存储
     * @param idGenerator  ID 生成器
     */
    public RagStreamTaskRegistry(RdTaskStore taskStore, SnowflakeIdGenerator idGenerator) {
        this(taskStore, null, idGenerator);
    }

    /**
     * 创建内存任务注册表（事件 store 为空时不写事件，向后兼容单测）。
     *
     * @return 内存任务注册表
     */
    public static RagStreamTaskRegistry inMemory() {
        return new RagStreamTaskRegistry(new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), SnowflakeIdGenerator.defaultGenerator());
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
        RdBugFixTask saved = taskStore.saveBugFixTask(task);
        recordEvent(saved, RdTaskStatus.CREATED.name(), saved.title(), "任务创建", RdTaskEventTrigger.SYSTEM);
        return saved;
    }

    /**
     * 创建或复用 Bug 修复任务。用于 MQ 重试/重复投递时保持同一工单只占用一个 RD 任务。
     *
     * @param ticket   工单快照
     * @param priority 优先级
     * @return 已存在任务或新任务快照
     */
    public synchronized RdBugFixTask createOrReuseBugFixTask(TicketSnapshot ticket, String priority) {
        TicketSnapshot safeTicket = normalizeTicket(ticket);
        if (!safeTicket.ticketId().isBlank()) {
            Optional<RdBugFixTask> existing = taskStore.findLatestBugFixTaskByTicketId(safeTicket.ticketId());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        return createBugFixTask(safeTicket, priority);
    }

    /**
     * 管理台创建任务：携带 prompt 快照与展示标题。
     *
     * @param ticketId       工单 ID
     * @param ticketTitle    工单标题
     * @param title          展示标题
     * @param priority       优先级
     * @param promptSnapshot Prompt 快照
     * @return 新任务快照
     */
    public synchronized RdBugFixTask createTaskManually(
            String ticketId,
            String ticketTitle,
            String title,
            String priority,
            String promptSnapshot
    ) {
        long now = System.currentTimeMillis();
        RdBugFixTask task = new RdBugFixTask(
                idGenerator.nextIdString(),
                RdBugFixTask.TASK_TYPE,
                ticketId,
                ticketTitle,
                priority,
                RdTaskStatus.CREATED,
                "",
                title == null || title.isBlank() ? (ticketTitle == null ? "" : ticketTitle) : title,
                promptSnapshot,
                "",
                "",
                "",
                now,
                now,
                false
        );
        RdBugFixTask saved = taskStore.saveBugFixTask(task);
        recordEvent(saved, RdTaskStatus.CREATED.name(), saved.title(), "管理台创建任务", RdTaskEventTrigger.API);
        return saved;
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
        return transitionAndSave(existing, RdTaskStatus.SEARCHING, "", summary, "", "", "", "");
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
        return transitionAndSave(existing, RdTaskStatus.EXECUTING, "", "", promptSnapshot, "", "", "");
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
        return transitionAndSave(
                existing,
                RdTaskStatus.COMMITTED,
                "",
                "",
                "",
                executionResultJson,
                pullRequestUrl,
                ""
        );
    }

    /**
     * 将任务推进到 MERGED。
     *
     * @param taskId 任务 ID
     * @return 新任务快照
     */
    public synchronized RdBugFixTask markMerged(String taskId) {
        RdBugFixTask existing = get(taskId);
        return transitionAndSave(existing, RdTaskStatus.MERGED, "", "", "", "", "", "");
    }

    /**
     * 将任务推进到 REJECTED。
     *
     * @param taskId       任务 ID
     * @param errorMessage 错误或 RD 打回原因
     * @return 新任务快照
     */
    public synchronized RdBugFixTask markRejected(String taskId, String errorMessage) {
        return markRejected(taskId, errorMessage, "");
    }

    /**
     * 将任务推进到 REJECTED，并保留执行器结构化结果。
     *
     * @param taskId              任务 ID
     * @param errorMessage        错误或 RD 打回原因
     * @param executionResultJson 执行器返回的结构化结果 JSON
     * @return 新任务快照
     */
    public synchronized RdBugFixTask markRejected(
            String taskId,
            String errorMessage,
            String executionResultJson
    ) {
        RdBugFixTask existing = get(taskId);
        return transitionAndSave(existing, RdTaskStatus.REJECTED, "", "", "", executionResultJson, "", errorMessage);
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
        return transitionAndSave(existing, RdTaskStatus.SEARCHING, "", "", "", "", "", "");
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
                ? transitionAndSave(existing, RdTaskStatus.EXECUTING, "", "", "", "", "", "")
                : existing;
        return transitionAndSave(executing, RdTaskStatus.COMMITTED, messageId, title, "", "", "", "");
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
        return transitionAndSave(existing, RdTaskStatus.REJECTED, "", "", "", "", "", "任务已停止");
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
        return transitionAndSave(existing, RdTaskStatus.REJECTED, messageId, "", "", "", "", errorMessage);
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

    /**
     * 按管理台查询条件分页查询任务（过滤掉 DELETED）。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    public synchronized RdTaskPage queryBugFixTasks(RdTaskQuery query) {
        RdTaskQuery safeQuery = query == null ? new RdTaskQuery(null, null, null, null, 1, 20) : query;
        List<RdBugFixTask> filtered = taskStore.listBugFixTasks().stream()
                .filter(task -> task.status() != RdTaskStatus.DELETED)
                .filter(task -> safeQuery.matchesStatus(task.status()))
                .filter(task -> safeQuery.matchesPriority(task.priority()))
                .filter(task -> safeQuery.matchesKeywords(task.ticketId(), task.ticketTitle(), task.title()))
                .sorted(Comparator.comparingLong(RdBugFixTask::updateTimeEpochMillis).reversed()
                        .thenComparing(RdBugFixTask::taskId))
                .toList();
        int total = filtered.size();
        int pageSize = safeQuery.pageSize();
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        int fromIndex = Math.min((safeQuery.page() - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        return new RdTaskPage(filtered.subList(fromIndex, toIndex), total, safeQuery.page(), pageSize, pages);
    }

    /**
     * 修改任务的标题 / 优先级 / 工单标题（不动状态机）。
     *
     * @param taskId      任务 ID
     * @param title       展示标题（空则保留原值）
     * @param priority    优先级（空则保留原值）
     * @param ticketTitle 工单标题（空则保留原值）
     * @return 更新后任务快照
     */
    public synchronized RdBugFixTask updateTask(String taskId, String title, String priority, String ticketTitle) {
        RdBugFixTask existing = get(taskId);
        RdBugFixTask updated = existing.withEditedFields(title, priority, ticketTitle, System.currentTimeMillis());
        return taskStore.saveBugFixTask(updated);
    }

    /**
     * 管理台暂停任务（仅标记，不改变状态机合法性）。
     *
     * @param taskId  任务 ID
     * @param message 暂停说明
     * @return 更新后任务快照
     */
    public synchronized RdBugFixTask pause(String taskId, String message) {
        RdBugFixTask existing = get(taskId);
        RdBugFixTask paused = existing.withPaused(true, System.currentTimeMillis());
        RdBugFixTask saved = taskStore.saveBugFixTask(paused);
        recordEvent(saved, RdTaskStatusEvent.ACTION_PAUSED, saved.title(), message, RdTaskEventTrigger.API);
        return saved;
    }

    /**
     * 管理台恢复任务。
     *
     * @param taskId  任务 ID
     * @param message 恢复说明
     * @return 更新后任务快照
     */
    public synchronized RdBugFixTask resume(String taskId, String message) {
        RdBugFixTask existing = get(taskId);
        RdBugFixTask resumed = existing.withPaused(false, System.currentTimeMillis());
        RdBugFixTask saved = taskStore.saveBugFixTask(resumed);
        recordEvent(saved, RdTaskStatusEvent.ACTION_RESUMED, saved.title(), message, RdTaskEventTrigger.API);
        return saved;
    }

    /**
     * 管理台逻辑删除任务（状态置 DELETED）并清理状态事件。
     *
     * @param taskId 任务 ID
     * @return 是否删除（任务不存在或已删除返回 false）
     */
    public synchronized boolean deleteTask(String taskId) {
        String safeTaskId = requireTaskId(taskId);
        return taskStore.findBugFixTask(safeTaskId).map(existing -> {
            if (existing.status() == RdTaskStatus.DELETED) {
                return false;
            }
            RdBugFixTask deleted = existing.deleted(System.currentTimeMillis());
            recordEvent(deleted, RdTaskStatusEvent.ACTION_DELETED, deleted.title(), "管理台删除任务", RdTaskEventTrigger.API);
            taskStore.saveBugFixTask(deleted);
            if (eventStore != null) {
                eventStore.deleteByTask(safeTaskId);
            }
            return true;
        }).orElse(false);
    }

    /**
     * 查询任务全链路状态事件时间线（按进入时间升序）。
     *
     * @param taskId 任务 ID
     * @return 状态事件列表（升序）
     */
    public synchronized List<RdTaskStatusEvent> timeline(String taskId) {
        requireTaskId(taskId);
        if (eventStore == null) {
            return List.of();
        }
        return eventStore.listByTask(taskId);
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
            case REJECTED -> target == RdTaskStatus.SEARCHING || target == RdTaskStatus.EXECUTING;
            case MERGED -> false;
            case DELETED -> false;
        };
        if (!legal) {
            throw new IllegalStateException("illegal task status transition: " + source + " -> " + target);
        }
    }

    private RdBugFixTask save(RdBugFixTask task) {
        return taskStore.saveBugFixTask(task);
    }

    /**
     * 应用状态机转换：校验合法性、生成新快照、落库，并在状态真实变化时追加一条状态事件。
     */
    private RdBugFixTask transitionAndSave(
            RdBugFixTask existing,
            RdTaskStatus targetStatus,
            String messageId,
            String title,
            String promptSnapshot,
            String executionResultJson,
            String pullRequestUrl,
            String errorMessage
    ) {
        if (existing.status() == targetStatus) {
            return existing;
        }
        ensureTransition(existing.status(), targetStatus);
        RdBugFixTask next = existing.withState(
                targetStatus,
                messageId,
                title,
                promptSnapshot,
                executionResultJson,
                pullRequestUrl,
                errorMessage,
                System.currentTimeMillis()
        );
        RdBugFixTask saved = taskStore.saveBugFixTask(next);
        String message = saved.errorMessage().isBlank() ? "" : saved.errorMessage();
        recordEvent(saved, saved.status().name(), saved.title(), message, RdTaskEventTrigger.SYSTEM);
        return saved;
    }

    /**
     * 记录一条状态事件。duration = 当前时刻 − 上一事件进入时刻，首条为 0。
     * eventStore 为空（向后兼容）时直接返回。
     */
    private void recordEvent(
            RdBugFixTask task,
            String status,
            String title,
            String message,
            RdTaskEventTrigger trigger
    ) {
        if (eventStore == null) {
            return;
        }
        long now = System.currentTimeMillis();
        List<RdTaskStatusEvent> existing = eventStore.listByTask(task.taskId());
        long previousEnteredAt = existing.isEmpty() ? now : existing.get(existing.size() - 1).enteredAtEpochMillis();
        long duration = Math.max(0L, now - previousEnteredAt);
        RdTaskStatusEvent event = new RdTaskStatusEvent(
                idGenerator.nextIdString(),
                task.taskId(),
                status,
                title,
                message == null ? "" : message,
                now,
                duration,
                trigger.name()
        );
        eventStore.save(event);
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
