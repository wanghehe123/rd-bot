package com.wish.rd.rag.runtime;

import com.wish.rd.rag.lock.DistributedLockExecutor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 内存 RD 任务状态事件存储。
 *
 * <p>供单测和无 PostgreSQL 的本地启动使用，通过 {@link DistributedLockExecutor} 保护内部事件快照。
 * 事件按 taskId 分组，查询时按 enteredAt 升序返回。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public final class InMemoryRdTaskStatusEventStore implements RdTaskStatusEventStore {

    private static final String LOCK_NAME = "rd-bot:lock:rd-task-status-event-store";

    private final LinkedHashMap<String, List<RdTaskStatusEvent>> eventsByTask = new LinkedHashMap<>();
    private final DistributedLockExecutor lockExecutor;

    private static final Comparator<RdTaskStatusEvent> BY_ENTERED =
            Comparator.comparingLong(RdTaskStatusEvent::enteredAtEpochMillis)
                    .thenComparing(RdTaskStatusEvent::id);

    public InMemoryRdTaskStatusEventStore() {
        this(DistributedLockExecutor.local());
    }

    @Autowired
    public InMemoryRdTaskStatusEventStore(ObjectProvider<DistributedLockExecutor> lockExecutorProvider) {
        this(lockExecutorProvider == null
                ? DistributedLockExecutor.local()
                : lockExecutorProvider.getIfAvailable(DistributedLockExecutor::local));
    }

    public InMemoryRdTaskStatusEventStore(DistributedLockExecutor lockExecutor) {
        this.lockExecutor = lockExecutor == null ? DistributedLockExecutor.local() : lockExecutor;
    }

    @Override
    public RdTaskStatusEvent save(RdTaskStatusEvent event) {
        return withLock(() -> {
            eventsByTask.computeIfAbsent(event.taskId(), ignored -> new ArrayList<>()).add(event);
            return event;
        });
    }

    @Override
    public List<RdTaskStatusEvent> listByTask(String taskId) {
        return withLock(() -> {
            String safeTaskId = taskId == null ? "" : taskId.strip();
            List<RdTaskStatusEvent> events = eventsByTask.get(safeTaskId);
            if (events == null || events.isEmpty()) {
                return List.of();
            }
            return events.stream().sorted(BY_ENTERED).toList();
        });
    }

    @Override
    public int deleteByTask(String taskId) {
        return withLock(() -> {
            String safeTaskId = taskId == null ? "" : taskId.strip();
            List<RdTaskStatusEvent> removed = eventsByTask.remove(safeTaskId);
            return removed == null ? 0 : removed.size();
        });
    }

    private <T> T withLock(Supplier<T> action) {
        return lockExecutor.execute(LOCK_NAME, action);
    }
}
