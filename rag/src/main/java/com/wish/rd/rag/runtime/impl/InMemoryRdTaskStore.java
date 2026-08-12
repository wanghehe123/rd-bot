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
    private final LinkedHashMap<String, Long> fencingTokens = new LinkedHashMap<>();
    private long nextFencingToken = 1L;
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
            RdBugFixTask existing = bugFixTasks.get(task.taskId());
            RdBugFixTask stored = existing == null
                    ? withInitialPersistenceMetadata(task)
                    : saveExistingBugFixTask(task, existing);
            bugFixTasks.put(task.taskId(), stored);
            versions.put(task.taskId(), stored.version());
            fencingTokens.put(task.taskId(), stored.fencingToken());
            return stored;
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
            RdRequirementTask existing = requirementTasks.get(task.taskId());
            RdRequirementTask stored = existing == null
                    ? withInitialPersistenceMetadata(task)
                    : saveExistingRequirementTask(task, existing);
            requirementTasks.put(task.taskId(), stored);
            versions.put(task.taskId(), stored.version());
            fencingTokens.put(task.taskId(), stored.fencingToken());
            return stored;
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
    public Optional<Long> findFencingToken(String taskId) {
        return withLock(() -> {
            String safeTaskId = taskId == null ? "" : taskId.strip();
            if (!bugFixTasks.containsKey(safeTaskId) && !requirementTasks.containsKey(safeTaskId)) {
                return Optional.empty();
            }
            return Optional.of(fencingTokens.getOrDefault(safeTaskId, 0L));
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
        advanceStatusWithExpectedVersion(
                taskId, expectedVersion, -1L, expectedStatus, newStatus,
                errorMessage, executionResultJson, pullRequestUrl, promptSnapshot
        );
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
        withLock(() -> {
            if (expectedStatus == null || newStatus == null) {
                throw new IllegalArgumentException("expectedStatus and newStatus must not be null");
            }
            String safeTaskId = taskId == null ? "" : taskId.strip();
            long currentVersion = versions.getOrDefault(safeTaskId, 0L);
            long currentFencingToken = fencingTokens.getOrDefault(safeTaskId, 0L);
            RdRequirementTask requirement = requirementTasks.get(safeTaskId);
            RdBugFixTask bugFix = bugFixTasks.get(safeTaskId);
            if (requirement == null && bugFix == null) {
                throw new IllegalStateException("stale or mismatched rd_tasks CAS for id=" + safeTaskId);
            }
            RdTaskStatus currentStatus = requirement != null ? requirement.status() : bugFix.status();
            if (currentVersion != expectedVersion
                    || currentStatus != expectedStatus
                    || (expectedFencingToken >= 0L && currentFencingToken != expectedFencingToken)) {
                throw new IllegalStateException(
                        "stale or mismatched rd_tasks CAS for id=" + safeTaskId
                                + " expectedVersion=" + expectedVersion
                                + " expectedStatus=" + expectedStatus
                                + " expectedFencingToken=" + expectedFencingToken
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
                ).withConcurrency(currentVersion + 1L, currentFencingToken + 1L));
            } else {
                String reason = errorMessage == null ? bugFix.errorMessage() : errorMessage;
                String resultJson = executionResultJson == null
                        ? bugFix.executionResultJson()
                        : executionResultJson;
                String prUrl = pullRequestUrl == null ? bugFix.pullRequestUrl() : pullRequestUrl;
                String prompt = promptSnapshot == null ? "" : promptSnapshot;
                bugFixTasks.put(safeTaskId, bugFix.withState(
                        newStatus, "", "", prompt, resultJson, prUrl, reason, now
                ).withConcurrency(currentVersion + 1L, currentFencingToken + 1L));
            }
            versions.put(safeTaskId, currentVersion + 1L);
            fencingTokens.put(safeTaskId, currentFencingToken + 1L);
            return null;
        });
    }

    @Override
    public void updateTaskWithExpectedVersion(
            com.wish.rd.rag.runtime.model.RdTask task,
            long expectedVersion,
            long expectedFencingToken,
            RdTaskStatus expectedStatus
    ) {
        withLock(() -> {
            if (task == null || expectedStatus == null) {
                throw new IllegalArgumentException("task and expectedStatus must not be null");
            }
            String safeTaskId = task.taskId() == null ? "" : task.taskId().strip();
            long currentVersion = versions.getOrDefault(safeTaskId, 0L);
            long currentFencingToken = fencingTokens.getOrDefault(safeTaskId, 0L);
            RdRequirementTask requirement = requirementTasks.get(safeTaskId);
            RdBugFixTask bugFix = bugFixTasks.get(safeTaskId);
            if (requirement == null && bugFix == null) {
                throw new IllegalStateException("stale or missing rd_tasks snapshot for id=" + safeTaskId);
            }
            RdTaskStatus currentStatus = requirement != null ? requirement.status() : bugFix.status();
            if (currentVersion != expectedVersion
                    || currentStatus != expectedStatus
                    || currentFencingToken != expectedFencingToken) {
                throw new IllegalStateException(
                        "stale or mismatched rd_tasks snapshot for id=" + safeTaskId
                                + " expectedVersion=" + expectedVersion
                                + " expectedStatus=" + expectedStatus
                                + " expectedFencingToken=" + expectedFencingToken
                );
            }
            long nextVersion = currentVersion + 1L;
            long nextFencingToken = currentFencingToken + 1L;
            if (task instanceof RdRequirementTask requirementTask) {
                requirementTasks.put(safeTaskId, requirementTask.withConcurrency(nextVersion, nextFencingToken));
            } else if (task instanceof RdBugFixTask bugFixTask) {
                bugFixTasks.put(safeTaskId, bugFixTask.withConcurrency(nextVersion, nextFencingToken));
            } else {
                throw new IllegalArgumentException("unsupported rd task type: " + task.getClass().getName());
            }
            versions.put(safeTaskId, nextVersion);
            fencingTokens.put(safeTaskId, nextFencingToken);
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

    /**
     * Restores the exact task snapshot observed before a coordinated local mutation.
     *
     * <p>This is intentionally package-private: only {@link CoordinatedRdTaskStatePersistence}
     * uses it to compensate for a timeline write failure inside this store's atomic boundary.
     */
    void restoreTaskSnapshot(String taskId, com.wish.rd.rag.runtime.model.RdTask snapshot) {
        withLock(() -> {
            String safeTaskId = taskId == null ? "" : taskId.strip();
            bugFixTasks.remove(safeTaskId);
            requirementTasks.remove(safeTaskId);
            versions.remove(safeTaskId);
            fencingTokens.remove(safeTaskId);
            if (snapshot instanceof RdBugFixTask bugFixTask) {
                bugFixTasks.put(safeTaskId, bugFixTask);
                versions.put(safeTaskId, bugFixTask.version());
                fencingTokens.put(safeTaskId, bugFixTask.fencingToken());
            } else if (snapshot instanceof RdRequirementTask requirementTask) {
                requirementTasks.put(safeTaskId, requirementTask);
                versions.put(safeTaskId, requirementTask.version());
                fencingTokens.put(safeTaskId, requirementTask.fencingToken());
            } else if (snapshot != null) {
                throw new IllegalArgumentException("unsupported rd task type: " + snapshot.getClass().getName());
            }
            return null;
        });
    }

    /** Executes a coordinated task/event mutation under the same reentrant store lock as CAS writes. */
    <T> T executeAtomically(Supplier<T> action) {
        return withLock(action);
    }

    private <T> T withLock(Supplier<T> action) {
        return lockExecutor.execute(LOCK_NAME, action);
    }

    private RdBugFixTask withInitialPersistenceMetadata(RdBugFixTask task) {
        long token = task.fencingToken() > 0L ? task.fencingToken() : nextFencingToken++;
        nextFencingToken = Math.max(nextFencingToken, token + 1L);
        return task.withConcurrency(task.version(), token);
    }

    private RdRequirementTask withInitialPersistenceMetadata(RdRequirementTask task) {
        long token = task.fencingToken() > 0L ? task.fencingToken() : nextFencingToken++;
        nextFencingToken = Math.max(nextFencingToken, token + 1L);
        return task.withConcurrency(task.version(), token);
    }

    private static RdBugFixTask saveExistingBugFixTask(RdBugFixTask task, RdBugFixTask existing) {
        ensureMatchingSnapshot(task.status(), task.version(), task.fencingToken(),
                existing.status(), existing.version(), existing.fencingToken(), task.taskId());
        return task.withConcurrency(existing.version() + 1L, existing.fencingToken() + 1L);
    }

    private static RdRequirementTask saveExistingRequirementTask(
            RdRequirementTask task,
            RdRequirementTask existing
    ) {
        ensureMatchingSnapshot(task.status(), task.version(), task.fencingToken(),
                existing.status(), existing.version(), existing.fencingToken(), task.taskId());
        return task.withConcurrency(existing.version() + 1L, existing.fencingToken() + 1L);
    }

    private static void ensureMatchingSnapshot(
            RdTaskStatus status,
            long version,
            long fencingToken,
            RdTaskStatus expectedStatus,
            long expectedVersion,
            long expectedFencingToken,
            String taskId
    ) {
        if (status != expectedStatus || version != expectedVersion || fencingToken != expectedFencingToken) {
            throw new IllegalStateException("stale or mismatched rd_tasks snapshot save for id=" + taskId);
        }
    }
}
