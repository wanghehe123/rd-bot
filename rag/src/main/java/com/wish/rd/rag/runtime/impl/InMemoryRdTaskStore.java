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
import com.wish.rd.rag.runtime.model.RdTaskStatus;

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
    private final LinkedHashMap<String, Long> versions = new LinkedHashMap<>();
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
            versions.putIfAbsent(task.taskId(), 0L);
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
            versions.putIfAbsent(task.taskId(), 0L);
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
    public Optional<Long> findVersion(String taskId) {
        return withLock(() -> {
            String safeTaskId = taskId == null ? "" : taskId.strip();
            if (!bugFixTasks.containsKey(safeTaskId) && !requirementTasks.containsKey(safeTaskId)) {
                return Optional.empty();
            }
            return Optional.of(versions.getOrDefault(safeTaskId, 0L));
        });
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
        withLock(() -> {
            if (expectedStatus == null || newStatus == null) {
                throw new IllegalArgumentException("expectedStatus and newStatus must not be null");
            }
            String safeTaskId = taskId == null ? "" : taskId.strip();
            long currentVersion = versions.getOrDefault(safeTaskId, 0L);
            RdRequirementTask requirement = requirementTasks.get(safeTaskId);
            RdBugFixTask bugFix = bugFixTasks.get(safeTaskId);
            if (requirement == null && bugFix == null) {
                throw new IllegalStateException("stale or mismatched rd_tasks CAS for id=" + safeTaskId);
            }
            RdTaskStatus currentStatus = requirement != null ? requirement.status() : bugFix.status();
            if (currentVersion != expectedVersion || currentStatus != expectedStatus) {
                throw new IllegalStateException(
                        "stale or mismatched rd_tasks CAS for id=" + safeTaskId
                                + " expectedVersion=" + expectedVersion
                                + " expectedStatus=" + expectedStatus
                );
            }
            long now = System.currentTimeMillis();
            // null payload fields leave the stored value unchanged (matches Postgres CAS).
            if (requirement != null) {
                String reason = errorMessage == null ? requirement.errorMessage() : errorMessage;
                String resultJson = executionResultJson == null
                        ? requirement.executionResultJson()
                        : executionResultJson;
                String prUrl = pullRequestUrl == null ? requirement.pullRequestUrl() : pullRequestUrl;
                String prompt = promptSnapshot == null ? "" : promptSnapshot;
                requirementTasks.put(safeTaskId, requirement.withState(
                        newStatus, prompt, resultJson, prUrl, reason, now
                ));
            } else {
                String reason = errorMessage == null ? bugFix.errorMessage() : errorMessage;
                String resultJson = executionResultJson == null
                        ? bugFix.executionResultJson()
                        : executionResultJson;
                String prUrl = pullRequestUrl == null ? bugFix.pullRequestUrl() : pullRequestUrl;
                String prompt = promptSnapshot == null ? "" : promptSnapshot;
                bugFixTasks.put(safeTaskId, bugFix.withState(
                        newStatus, "", "", prompt, resultJson, prUrl, reason, now
                ));
            }
            versions.put(safeTaskId, currentVersion + 1L);
            return null;
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
