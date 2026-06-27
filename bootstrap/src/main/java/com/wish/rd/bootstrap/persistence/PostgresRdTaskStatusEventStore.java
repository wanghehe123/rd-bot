package com.wish.rd.bootstrap.persistence;

import com.wish.rd.bootstrap.persistence.entity.RdTaskStatusEventRow;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskStatusEventMapper;
import com.wish.rd.rag.runtime.RdTaskStatusEvent;
import com.wish.rd.rag.runtime.RdTaskStatusEventStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL RD 任务状态事件存储适配器。
 *
 * <p>供 {@link com.wish.rd.rag.runtime.RagStreamTaskRegistry} 落库状态事件时间线。
 * 事件为 append-only，按 enteredAt 升序返回。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresRdTaskStatusEventStore implements RdTaskStatusEventStore {

    private final RdTaskStatusEventMapper eventMapper;

    public PostgresRdTaskStatusEventStore(RdTaskStatusEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    @Override
    public RdTaskStatusEvent save(RdTaskStatusEvent event) {
        RdTaskStatusEventRow row = toRow(event);
        eventMapper.insert(row);
        return event;
    }

    @Override
    public List<RdTaskStatusEvent> listByTask(String taskId) {
        long rawTaskId = Optional.ofNullable(taskId).filter(s -> !s.isBlank())
                .map(s -> {
                    try {
                        return PostgresPersistenceSupport.parseId(s);
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                })
                .orElse(-1L);
        if (rawTaskId < 0L) {
            return List.of();
        }
        return eventMapper.listByTask(rawTaskId).stream()
                .map(this::toEvent)
                .toList();
    }

    @Override
    public int deleteByTask(String taskId) {
        long rawTaskId = Optional.ofNullable(taskId).filter(s -> !s.isBlank())
                .map(s -> {
                    try {
                        return PostgresPersistenceSupport.parseId(s);
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                })
                .orElse(-1L);
        if (rawTaskId < 0L) {
            return 0;
        }
        return eventMapper.deleteByTask(rawTaskId);
    }

    private RdTaskStatusEventRow toRow(RdTaskStatusEvent event) {
        RdTaskStatusEventRow row = new RdTaskStatusEventRow();
        row.id = PostgresPersistenceSupport.parseId(event.id());
        row.taskId = PostgresPersistenceSupport.parseId(event.taskId());
        row.status = event.status();
        row.title = event.title();
        row.message = event.message();
        row.enteredAt = PostgresPersistenceSupport.toDateTime(event.enteredAtEpochMillis());
        row.durationMs = event.durationMillis();
        row.trigger = event.trigger();
        return row;
    }

    private RdTaskStatusEvent toEvent(RdTaskStatusEventRow row) {
        return new RdTaskStatusEvent(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.taskId),
                row.status,
                row.title,
                row.message,
                PostgresPersistenceSupport.toEpochMillis(row.enteredAt),
                row.durationMs == null ? 0L : row.durationMs,
                row.trigger
        );
    }
}
