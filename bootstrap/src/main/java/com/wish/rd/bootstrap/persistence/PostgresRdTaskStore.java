package com.wish.rd.bootstrap.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.persistence.entity.RdTaskRow;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskMapper;
import com.wish.rd.rag.runtime.RdBugFixTask;
import com.wish.rd.rag.runtime.RdTaskStatus;
import com.wish.rd.rag.runtime.RdTaskStore;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL RD 任务存储适配器。
 *
 * <p>供 {@link com.wish.rd.rag.runtime.RagStreamTaskRegistry} 落库任务状态机快照。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresRdTaskStore implements RdTaskStore {

    private final RdTaskMapper taskMapper;

    public PostgresRdTaskStore(RdTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Override
    public RdBugFixTask saveBugFixTask(RdBugFixTask task) {
        taskMapper.upsertTask(toRow(task));
        return task;
    }

    @Override
    public Optional<RdBugFixTask> findBugFixTask(String taskId) {
        return Optional.ofNullable(taskMapper.selectById(PostgresPersistenceSupport.parseId(taskId)))
                .map(this::toTask);
    }

    @Override
    public List<RdBugFixTask> listBugFixTasks() {
        return taskMapper.selectList(new QueryWrapper<RdTaskRow>()
                        .eq("task_type", RdBugFixTask.TASK_TYPE))
                .stream()
                .sorted(Comparator
                        .comparing((RdTaskRow row) -> row.updatedAt)
                        .thenComparing(row -> row.id))
                .map(this::toTask)
                .toList();
    }

    private RdTaskRow toRow(RdBugFixTask task) {
        RdTaskRow row = new RdTaskRow();
        row.id = PostgresPersistenceSupport.parseId(task.taskId());
        row.taskType = task.taskType();
        row.ticketId = task.ticketId();
        row.ticketTitle = task.ticketTitle();
        row.priority = task.priority();
        row.status = task.status().name();
        row.messageId = task.messageId();
        row.title = task.title();
        row.promptSnapshot = task.promptSnapshot();
        row.executionResultJson = task.executionResultJson().isBlank() ? "{}" : task.executionResultJson();
        row.pullRequestUrl = task.pullRequestUrl();
        row.errorMessage = task.errorMessage();
        row.createdAt = PostgresPersistenceSupport.toDateTime(task.createTimeEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(task.updateTimeEpochMillis());
        return row;
    }

    private RdBugFixTask toTask(RdTaskRow row) {
        return new RdBugFixTask(
                PostgresPersistenceSupport.idString(row.id),
                row.taskType,
                row.ticketId,
                row.ticketTitle,
                row.priority,
                RdTaskStatus.valueOf(row.status),
                row.messageId,
                row.title,
                row.promptSnapshot,
                row.executionResultJson,
                row.pullRequestUrl,
                row.errorMessage,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }
}
