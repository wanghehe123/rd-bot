package com.wish.rd.rag.runtime.impl;

import com.wish.rd.rag.runtime.RdTaskStore;

import com.wish.rd.rag.lock.DistributedLockExecutor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdRequirementTask;

/**
 * 内存 RD 任务存储。
 *
 * <p>供单测和无 PostgreSQL 的本地启动使用，通过 {@link DistributedLockExecutor} 保护内部快照。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public final class InMemoryRdTaskStore implements RdTaskStore {

    private static final String LOCK_NAME = "rd-bot:lock:rd-task-store";

    private final LinkedHashMap<String, RdBugFixTask> bugFixTasks = new LinkedHashMap<>();
    private final LinkedHashMap<String, RdRequirementTask> requirementTasks = new LinkedHashMap<>();
    private final DistributedLockExecutor lockExecutor;

    public InMemoryRdTaskStore() {
        this(DistributedLockExecutor.local());
    }

    @Autowired
    public InMemoryRdTaskStore(ObjectProvider<DistributedLockExecutor> lockExecutorProvider) {
        this(lockExecutorProvider == null
                ? DistributedLockExecutor.local()
                : lockExecutorProvider.getIfAvailable(DistributedLockExecutor::local));
    }

    public InMemoryRdTaskStore(DistributedLockExecutor lockExecutor) {
        this.lockExecutor = lockExecutor == null ? DistributedLockExecutor.local() : lockExecutor;
    }

    @Override
    public RdBugFixTask saveBugFixTask(RdBugFixTask task) {
        return withLock(() -> {
            bugFixTasks.put(task.taskId(), task);
            return task;
        });
    }

    @Override
    public Optional<RdBugFixTask> findBugFixTask(String taskId) {
        return withLock(() -> {
            String safeTaskId = taskId == null ? "" : taskId.strip();
            return Optional.ofNullable(bugFixTasks.get(safeTaskId));
        });
    }

    @Override
    public RdRequirementTask saveRequirementTask(RdRequirementTask task) {
        return withLock(() -> {
            requirementTasks.put(task.taskId(), task);
            return task;
        });
    }

    @Override
    public Optional<RdRequirementTask> findRequirementTask(String taskId) {
        return withLock(() -> {
            String safeTaskId = taskId == null ? "" : taskId.strip();
            return Optional.ofNullable(requirementTasks.get(safeTaskId));
        });
    }

    @Override
    public List<RdBugFixTask> listBugFixTasks() {
        return withLock(() -> List.copyOf(bugFixTasks.values()));
    }

    @Override
    public List<RdRequirementTask> listRequirementTasks() {
        return withLock(() -> List.copyOf(requirementTasks.values()));
    }

    private <T> T withLock(Supplier<T> action) {
        return lockExecutor.execute(LOCK_NAME, action);
    }
}
