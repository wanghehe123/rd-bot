package com.wish.rd.rag.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 内存 RD 任务状态事件存储。
 *
 * <p>供单测和无 PostgreSQL 的本地启动使用，写操作用 {@code synchronized} 保证一致。
 * 事件按 taskId 分组，查询时按 enteredAt 升序返回。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public final class InMemoryRdTaskStatusEventStore implements RdTaskStatusEventStore {

    private final LinkedHashMap<String, List<RdTaskStatusEvent>> eventsByTask = new LinkedHashMap<>();

    private static final Comparator<RdTaskStatusEvent> BY_ENTERED =
            Comparator.comparingLong(RdTaskStatusEvent::enteredAtEpochMillis)
                    .thenComparing(RdTaskStatusEvent::id);

    @Override
    public synchronized RdTaskStatusEvent save(RdTaskStatusEvent event) {
        eventsByTask.computeIfAbsent(event.taskId(), ignored -> new ArrayList<>()).add(event);
        return event;
    }

    @Override
    public synchronized List<RdTaskStatusEvent> listByTask(String taskId) {
        String safeTaskId = taskId == null ? "" : taskId.strip();
        List<RdTaskStatusEvent> events = eventsByTask.get(safeTaskId);
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream().sorted(BY_ENTERED).toList();
    }

    @Override
    public synchronized int deleteByTask(String taskId) {
        String safeTaskId = taskId == null ? "" : taskId.strip();
        List<RdTaskStatusEvent> removed = eventsByTask.remove(safeTaskId);
        return removed == null ? 0 : removed.size();
    }
}
