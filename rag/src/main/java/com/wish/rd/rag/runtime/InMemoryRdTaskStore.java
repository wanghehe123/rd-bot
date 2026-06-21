package com.wish.rd.rag.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 内存 RD 任务存储。
 *
 * <p>供单测和无 PostgreSQL 的本地启动使用，写操作用 {@code synchronized} 保证快照一致。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public final class InMemoryRdTaskStore implements RdTaskStore {

    private final LinkedHashMap<String, RdBugFixTask> bugFixTasks = new LinkedHashMap<>();

    @Override
    public synchronized RdBugFixTask saveBugFixTask(RdBugFixTask task) {
        bugFixTasks.put(task.taskId(), task);
        return task;
    }

    @Override
    public synchronized Optional<RdBugFixTask> findBugFixTask(String taskId) {
        String safeTaskId = taskId == null ? "" : taskId.strip();
        return Optional.ofNullable(bugFixTasks.get(safeTaskId));
    }

    @Override
    public synchronized List<RdBugFixTask> listBugFixTasks() {
        return List.copyOf(bugFixTasks.values());
    }
}
