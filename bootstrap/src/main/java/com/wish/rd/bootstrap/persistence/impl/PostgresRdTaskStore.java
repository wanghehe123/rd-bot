package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.persistence.entity.RdTaskRow;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskMapper;
import com.wish.rd.rag.runtime.RdTaskStore;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;

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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RdTaskMapper taskMapper;

    public PostgresRdTaskStore(RdTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Override
    public RdBugFixTask saveBugFixTask(RdBugFixTask task) {
        RdTaskRow existing = taskMapper.selectById(PostgresPersistenceSupport.parseId(task.taskId()));
        if (existing == null) {
            RdTaskRow row = toRow(task);
            assignInitialFencingToken(row);
            taskMapper.upsertTask(row);
            return findBugFixTask(task.taskId()).orElse(task);
        }
        if (task.fencingToken() <= 0L
                || !task.status().name().equals(existing.status)
                || !sameConcurrency(task.version(), task.fencingToken(), existing)) {
            throw new IllegalStateException(
                    "existing rd task snapshots must use fenced CAS: " + task.taskId());
        }
        updateTaskWithExpectedVersion(task, task.version(), task.fencingToken(), task.status());
        return findBugFixTask(task.taskId()).orElse(task);
    }

    @Override
    public Optional<RdBugFixTask> findBugFixTask(String taskId) {
        return Optional.ofNullable(taskMapper.selectById(PostgresPersistenceSupport.parseId(taskId)))
                .filter(row -> RdBugFixTask.TASK_TYPE.equals(row.taskType))
                .map(this::toBugFixTask);
    }

    @Override
    public RdRequirementTask saveRequirementTask(RdRequirementTask task) {
        RdTaskRow existing = taskMapper.selectById(PostgresPersistenceSupport.parseId(task.taskId()));
        if (existing == null) {
            RdTaskRow row = toRow(task);
            assignInitialFencingToken(row);
            taskMapper.upsertTask(row);
            return findRequirementTask(task.taskId()).orElse(task);
        }
        if (task.fencingToken() <= 0L
                || !task.status().name().equals(existing.status)
                || !sameConcurrency(task.version(), task.fencingToken(), existing)) {
            throw new IllegalStateException(
                    "existing rd task snapshots must use fenced CAS: " + task.taskId());
        }
        updateTaskWithExpectedVersion(task, task.version(), task.fencingToken(), task.status());
        return findRequirementTask(task.taskId()).orElse(task);
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

    @Override
    public void advanceStatusWithExpectedVersion(
            String taskId,
            long expectedVersion,
            RdTaskStatus expectedStatus,
            RdTaskStatus newStatus,
            String errorMessage
    ) {
        advanceStatusWithExpectedVersion(
                taskId, expectedVersion, expectedStatus, newStatus, errorMessage, null, null
        );
    }

    @Override
    public void advanceStatusWithExpectedVersion(
            String taskId,
            long expectedVersion,
            RdTaskStatus expectedStatus,
            RdTaskStatus newStatus,
            String errorMessage,
            String executionResultJson,
            String pullRequestUrl,
            String promptSnapshot
    ) {
        throw new UnsupportedOperationException(
                "unfenced rd_tasks status writes are disabled; provide expected fencing token");
    }

    @Override
    public void updateTaskWithExpectedVersion(
            com.wish.rd.rag.runtime.model.RdTask task,
            long expectedVersion,
            long expectedFencingToken,
            RdTaskStatus expectedStatus
    ) {
        if (task == null || expectedStatus == null) {
            throw new IllegalArgumentException("task and expectedStatus must not be null");
        }
        requirePositiveFencingToken(expectedFencingToken);
        int updated = taskMapper.updateTaskWithExpectedVersionFenced(
                toRow(task),
                expectedVersion,
                expectedFencingToken,
                expectedStatus.name()
        );
        if (updated != 1) {
            throw new IllegalStateException(
                    "stale or mismatched fenced rd_tasks snapshot for id=" + task.taskId()
                            + " expectedVersion=" + expectedVersion
                            + " expectedFencingToken=" + expectedFencingToken
                            + " expectedStatus=" + expectedStatus
            );
        }
    }

    @Override
    public void advanceStatusWithExpectedVersion(
            String taskId,
            long expectedVersion,
            long expectedFencingToken,
            RdTaskStatus expectedStatus,
            RdTaskStatus newStatus,
            String errorMessage,
            String executionResultJson,
            String pullRequestUrl,
            String promptSnapshot
    ) {
        if (expectedStatus == null || newStatus == null) {
            throw new IllegalArgumentException("expectedStatus and newStatus must not be null");
        }
        requirePositiveFencingToken(expectedFencingToken);
        int updated = taskMapper.advanceStatusWithExpectedVersionFenced(
                PostgresPersistenceSupport.parseId(taskId),
                expectedVersion,
                expectedFencingToken,
                expectedStatus.name(),
                newStatus.name(),
                errorMessage,
                executionResultJson,
                pullRequestUrl,
                promptSnapshot,
                PostgresPersistenceSupport.toDateTime(System.currentTimeMillis())
        );
        if (updated != 1) {
            throw new IllegalStateException(
                    "stale or mismatched fenced rd_tasks CAS for id=" + taskId
                            + " expectedVersion=" + expectedVersion
                            + " expectedFencingToken=" + expectedFencingToken
                            + " expectedStatus=" + expectedStatus
            );
        }
    }

    /**
     * Reads the optimistic concurrency version for fencing callers.
     *
     * @param taskId task id
     * @return stored version, or empty when the row is missing
     */
    @Override
    public Optional<Long> findVersion(String taskId) {
        return Optional.ofNullable(taskMapper.selectById(PostgresPersistenceSupport.parseId(taskId)))
                .map(row -> row.version == null ? 0L : row.version);
    }

    @Override
    public Optional<Long> findFencingToken(String taskId) {
        return Optional.ofNullable(taskMapper.selectById(PostgresPersistenceSupport.parseId(taskId)))
                .map(row -> row.fencingToken == null ? 0L : row.fencingToken);
    }

    private RdTaskRow toRow(com.wish.rd.rag.runtime.model.RdTask task) {
        if (task instanceof RdBugFixTask bugFixTask) {
            return toRow(bugFixTask);
        }
        if (task instanceof RdRequirementTask requirementTask) {
            return toRow(requirementTask);
        }
        throw new IllegalArgumentException("unsupported rd task type: " + task.getClass().getName());
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
        row.projectId = task.projectId();
        row.projectKey = task.projectKey();
        row.projectName = task.projectName();
        row.repositoryUrl = task.repositoryUrl();
        row.repoOwner = task.repoOwner();
        row.repoName = task.repoName();
        row.baseBranch = task.baseBranch();
        row.workBranch = "";
        row.expectedResult = "";
        row.acceptanceCriteriaJson = "[]";
        row.hostAssertionBundleJson = null;
        row.tokenBudgetOverride = 0L;
        row.version = task.version();
        row.fencingToken = task.fencingToken();
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
        row.projectId = task.projectId();
        row.projectKey = task.projectKey();
        row.projectName = task.projectName();
        row.repositoryUrl = task.repositoryUrl();
        row.repoOwner = task.repoOwner();
        row.repoName = task.repoName();
        row.baseBranch = task.baseBranch();
        row.workBranch = task.workBranch();
        row.expectedResult = task.expectedResult();
        row.acceptanceCriteriaJson = task.acceptanceCriteriaJson().isBlank() ? "[]" : task.acceptanceCriteriaJson();
        row.hostAssertionBundleJson = writeHostAssertionBundle(task.hostAssertionBundle());
        row.tokenBudgetOverride = task.tokenBudgetOverride();
        row.version = task.version();
        row.fencingToken = task.fencingToken();
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
                row.projectId,
                row.projectKey,
                row.projectName,
                row.repositoryUrl,
                row.repoOwner,
                row.repoName,
                row.baseBranch,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt),
                row.paused != null && row.paused,
                row.version == null ? 0L : row.version,
                row.fencingToken == null ? 0L : row.fencingToken
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
                row.projectId,
                row.projectKey,
                row.projectName,
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
                row.paused != null && row.paused,
                row.tokenBudgetOverride == null ? 0L : row.tokenBudgetOverride,
                row.version == null ? 0L : row.version,
                row.fencingToken == null ? 0L : row.fencingToken,
                readHostAssertionBundle(row.hostAssertionBundleJson)
        );
    }

    private boolean sameConcurrency(long version, long fencingToken, RdTaskRow existing) {
        return (existing.version == null ? 0L : existing.version) == version
                && (existing.fencingToken == null ? 0L : existing.fencingToken) == fencingToken;
    }

    private static void assignInitialFencingToken(RdTaskRow row) {
        if (row.fencingToken == null || row.fencingToken <= 0L) {
            row.fencingToken = 1L;
        }
    }

    private static void requirePositiveFencingToken(long fencingToken) {
        if (fencingToken <= 0L) {
            throw new IllegalArgumentException("expectedFencingToken must be positive");
        }
    }

    private static String writeHostAssertionBundle(JsonNode bundle) {
        if (bundle == null || bundle.isNull()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(bundle);
        } catch (Exception exception) {
            throw new IllegalStateException("Host assertion task input cannot be serialized", exception);
        }
    }

    private static JsonNode readHostAssertionBundle(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(raw);
            return parsed == null || parsed.isNull() ? null : parsed;
        } catch (Exception exception) {
            throw new IllegalStateException("stored Host assertion task input is invalid", exception);
        }
    }
}
