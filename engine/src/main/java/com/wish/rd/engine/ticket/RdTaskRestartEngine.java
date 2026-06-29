package com.wish.rd.engine.ticket;

import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.RdBugFixTask;
import com.wish.rd.rag.runtime.RdTaskStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * RD 任务人工恢复与重新触发用例。
 *
 * <p>供管理台 {@code /admin/rd-tasks/{taskId}/resume} 调用：先清除暂停标记，再把同一任务
 * 标记为可重试状态，最后按原工单 ID 重新发布修复队列消息。实际 RAG 与 Docker 执行仍由
 * {@link TicketRepairExecutionConsumer} 消费队列后完成，避免管理台 HTTP 请求同步阻塞执行器。
 */
@Service
public class RdTaskRestartEngine {

    private final RagStreamTaskRegistry taskRegistry;
    private final RepairQueuePublisher queuePublisher;

    /**
     * 创建人工恢复与重新触发用例。
     *
     * @param taskRegistry          RD 任务状态注册表
     * @param queuePublisherProvider 修复队列发布端口
     */
    @Autowired
    public RdTaskRestartEngine(
            RagStreamTaskRegistry taskRegistry,
            ObjectProvider<RepairQueuePublisher> queuePublisherProvider
    ) {
        this(
                taskRegistry,
                queuePublisherProvider.getIfAvailable(() -> message -> RepairQueuePublishResult.failure(
                        "",
                        message == null ? "" : message.tag(),
                        "repair queue publisher unavailable"
                ))
        );
    }

    /**
     * 创建人工恢复与重新触发用例。
     *
     * @param taskRegistry  RD 任务状态注册表
     * @param queuePublisher 修复队列发布端口
     */
    public RdTaskRestartEngine(RagStreamTaskRegistry taskRegistry, RepairQueuePublisher queuePublisher) {
        this.taskRegistry = taskRegistry == null ? RagStreamTaskRegistry.inMemory() : taskRegistry;
        this.queuePublisher = queuePublisher == null
                ? message -> RepairQueuePublishResult.failure("", "", "repair queue publisher unavailable")
                : queuePublisher;
    }

    /**
     * 恢复暂停任务并重新发布修复队列消息。
     *
     * @param taskId  任务 ID
     * @param message 管理台操作说明
     * @return 重新入队前后的任务快照
     * @throws IllegalArgumentException 任务缺少工单 ID 时抛出
     * @throws IllegalStateException    任务为终态或队列发布失败时抛出
     */
    public RdBugFixTask resumeAndRestart(String taskId, String message) {
        RdBugFixTask current = taskRegistry.get(taskId);
        ensureRestartable(current);
        if (current.ticketId().isBlank()) {
            throw new IllegalArgumentException("ticketId must not be blank for task restart: " + current.taskId());
        }
        String operationMessage = normalizeMessage(message);
        RdBugFixTask resumed = taskRegistry.resume(current.taskId(), operationMessage);
        RdBugFixTask retryable = resumed.status() == RdTaskStatus.REJECTED
                ? resumed
                : taskRegistry.markRejected(resumed.taskId(), operationMessage);
        RepairQueuePublishResult publishResult = queuePublisher.publish(toRestartMessage(retryable));
        if (!publishResult.success()) {
            String reason = "管理台重启入队失败: " + publishResult.errorMessage();
            taskRegistry.markRejected(retryable.taskId(), reason);
            throw new IllegalStateException(reason);
        }
        return taskRegistry.get(retryable.taskId());
    }

    private void ensureRestartable(RdBugFixTask task) {
        if (task.status() == RdTaskStatus.MERGED || task.status() == RdTaskStatus.DELETED) {
            throw new IllegalStateException("terminal task cannot be restarted: " + task.status());
        }
    }

    private RepairTicketMessage toRestartMessage(RdBugFixTask task) {
        Instant now = Instant.now();
        return new RepairTicketMessage(
                task.ticketId(),
                task.priority(),
                "admin-resume-" + task.taskId(),
                RepairTicketMessage.FIRST_ATTEMPT,
                "admin",
                "admin-resume-" + task.taskId() + "-" + now.toEpochMilli(),
                "admin.rd-task.resume",
                now
        );
    }

    private String normalizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "管理台恢复并重启";
        }
        return message.strip();
    }
}
