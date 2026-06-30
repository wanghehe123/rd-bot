package com.wish.rd.bootstrap.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.persistence.entity.RdTaskRow;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskMapper;
import com.wish.rd.rag.runtime.RdBugFixTask;
import com.wish.rd.rag.runtime.RdRequirementTask;
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
                .filter(row -> RdBugFixTask.TASK_TYPE.equals(row.taskType))
                .map(this::toBugFixTask);
    }

    @Override
    public RdRequirementTask saveRequirementTask(RdRequirementTask task) {
        taskMapper.upsertTask(toRow(task));
        return task;
    }

    @Override
    public Optional<RdRequirementTask> findRequirementTask(String taskId) {
        return Optional.ofNullable(taskMapper.selectById(PostgresPersistenceSupport.parseId(taskId)))
                .filter(row -> RdRequirementTask.TASK_TYPE.equals(row.taskType))
                .map(this::toRequirementTask);
    }

    @Override
    public Optional<RdBugFixTask> findLatestBugFixTaskByTicketId(String ticketId) {
        String safeTicketId = ticketId == null ? "" : ticketId.strip();
        if (safeTicketId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(taskMapper.selectOne(new QueryWrapper<RdTaskRow>()
                        .eq("task_type", RdBugFixTask.TASK_TYPE)
                        .eq("ticket_id", safeTicketId)
                        .ne("status", RdTaskStatus.DELETED.name())
                        .orderByDesc("updated_at")
                        .orderByDesc("id")
                        .last("LIMIT 1")))
                .map(this::toBugFixTask);
    }

    @Override
    public List<RdBugFixTask> listBugFixTasks() {
        return taskMapper.selectList(new QueryWrapper<RdTaskRow>()
                        .eq("task_type", RdBugFixTask.TASK_TYPE))
                .stream()
                .sorted(Comparator
                        .comparing((RdTaskRow row) -> row.updatedAt)
                        .thenComparing(row -> row.id))
                .map(this::toBugFixTask)
                .toList();
    }

    @Override
    public List<RdRequirementTask> listRequirementTasks() {
        return taskMapper.selectList(new QueryWrapper<RdTaskRow>()
                        .eq("task_type", RdRequirementTask.TASK_TYPE))
                .stream()
                .sorted(Comparator
                        .comparing((RdTaskRow row) -> row.updatedAt)
                        .thenComparing(row -> row.id))
                .map(this::toRequirementTask)
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
        row.sourceType = "";
        row.sourceId = "";
        row.sourceUrl = "";
        row.repositoryUrl = "";
        row.repoOwner = "";
        row.repoName = "";
        row.baseBranch = "";
        row.workBranch = "";
        row.expectedResult = "";
        row.acceptanceCriteriaJson = "[]";
        row.createdAt = PostgresPersistenceSupport.toDateTime(task.createTimeEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(task.updateTimeEpochMillis());
        row.paused = task.paused();
        return row;
    }

    private RdTaskRow toRow(RdRequirementTask task) {
        RdTaskRow row = new RdTaskRow();
        row.id = PostgresPersistenceSupport.parseId(task.taskId());
        row.taskType = task.taskType();
        row.ticketId = "";
        row.ticketTitle = "";
        row.priority = task.priority();
        row.status = task.status().name();
        row.messageId = "";
        row.title = task.title();
        row.promptSnapshot = task.promptSnapshot();
        row.executionResultJson = task.executionResultJson().isBlank() ? "{}" : task.executionResultJson();
        row.pullRequestUrl = task.pullRequestUrl();
        row.errorMessage = task.errorMessage();
        row.sourceType = task.sourceType();
        row.sourceId = task.sourceId();
        row.sourceUrl = task.sourceUrl();
        row.repositoryUrl = task.repositoryUrl();
        row.repoOwner = task.repoOwner();
        row.repoName = task.repoName();
        row.baseBranch = task.baseBranch();
        row.workBranch = task.workBranch();
        row.expectedResult = task.expectedResult();
        row.acceptanceCriteriaJson = task.acceptanceCriteriaJson().isBlank() ? "[]" : task.acceptanceCriteriaJson();
        row.createdAt = PostgresPersistenceSupport.toDateTime(task.createTimeEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(task.updateTimeEpochMillis());
        row.paused = task.paused();
        return row;
    }

    private RdBugFixTask toBugFixTask(RdTaskRow row) {
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
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt),
                row.paused != null && row.paused
        );
    }

    private RdRequirementTask toRequirementTask(RdTaskRow row) {
        return new RdRequirementTask(
                PostgresPersistenceSupport.idString(row.id),
                row.taskType,
                row.sourceType,
                row.sourceId,
                row.sourceUrl,
                row.priority,
                RdTaskStatus.valueOf(row.status),
                row.title,
                row.repositoryUrl,
                row.repoOwner,
                row.repoName,
                row.baseBranch,
                row.workBranch,
                row.expectedResult,
                row.acceptanceCriteriaJson,
                row.promptSnapshot,
                row.executionResultJson,
                row.pullRequestUrl,
                row.errorMessage,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt),
                row.paused != null && row.paused
        );
    }
}
