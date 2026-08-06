package com.wish.rd.rag.runtime;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;

/**
 * RD 任务持久化端口。
 *
 * <p>rag 只依赖端口；内存实现用于测试和本地默认启动，PostgreSQL 适配器由 bootstrap 提供。
 */
public interface RdTaskStore {

    /**
     * 保存 Bug 修复任务快照。
     *
     * @param task 任务快照
     * @return 保存后的任务快照
     */
    RdBugFixTask saveBugFixTask(RdBugFixTask task);

    /**
     * 按任务 ID 查询 Bug 修复任务。
     *
     * @param taskId 任务 ID
     * @return 任务快照
     */
    Optional<RdBugFixTask> findBugFixTask(String taskId);

    /**
     * 保存需求交付任务快照。
     *
     * @param task 任务快照
     * @return 保存后的任务快照
     */
    default RdRequirementTask saveRequirementTask(RdRequirementTask task) {
        throw new UnsupportedOperationException("requirement task store is unavailable");
    }

    /**
     * 按任务 ID 查询需求交付任务。
     *
     * @param taskId 任务 ID
     * @return 任务快照
     */
    default Optional<RdRequirementTask> findRequirementTask(String taskId) {
        return Optional.empty();
    }

    /**
     * 按工单 ID 查询最近一个未删除的 Bug 修复任务。
     *
     * @param ticketId 工单 ID
     * @return 最近任务快照
     */
    default Optional<RdBugFixTask> findLatestBugFixTaskByTicketId(String ticketId) {
        String safeTicketId = ticketId == null ? "" : ticketId.strip();
        if (safeTicketId.isBlank()) {
            return Optional.empty();
        }
        return listBugFixTasks().stream()
                .filter(task -> task.status() != RdTaskStatus.DELETED)
                .filter(task -> safeTicketId.equals(task.ticketId()))
                .max(Comparator.comparingLong(RdBugFixTask::updateTimeEpochMillis)
                        .thenComparing(RdBugFixTask::taskId));
    }

    /**
     * 查询所有 Bug 修复任务快照。
     *
     * @return 任务快照列表
     */
    List<RdBugFixTask> listBugFixTasks();

    /**
     * 查询所有需求交付任务快照。
     *
     * @return 任务快照列表
     */
    default List<RdRequirementTask> listRequirementTasks() {
        return List.of();
    }

    /**
     * 按任务 ID 查询任意 RD 任务。
     *
     * @param taskId 任务 ID
     * @return 任务快照
     */
    default Optional<RdTask> findTask(String taskId) {
        Optional<? extends RdTask> bugFix = findBugFixTask(taskId);
        if (bugFix.isPresent()) {
            return Optional.of(bugFix.get());
        }
        Optional<? extends RdTask> requirement = findRequirementTask(taskId);
        return requirement.map(task -> task);
    }

    /**
     * 查询所有 RD 任务快照。
     *
     * @return 任务快照列表
     */
    default List<RdTask> listTasks() {
        List<RdTask> tasks = new ArrayList<>();
        tasks.addAll(listBugFixTasks());
        tasks.addAll(listRequirementTasks());
        return List.copyOf(tasks);
    }

    /**
     * Optimistic concurrency version for fencing status writers.
     *
     * @param taskId task id
     * @return stored version, or empty when the row/snapshot is missing
     */
    default Optional<Long> findVersion(String taskId) {
        return Optional.empty();
    }

    /**
     * Advances status only when stored {@code version} and {@code status} match; increments version.
     * Cancel / pause / worker completion races must use this instead of blind {@code save*Task}.
     *
     * @param taskId          task id
     * @param expectedVersion expected optimistic version
     * @param expectedStatus  expected current status
     * @param newStatus       status to write
     * @param errorMessage    optional error / cancel reason (null keeps existing where supported)
     * @throws IllegalStateException when zero rows match (stale writer / status race)
     */
    default void advanceStatusWithExpectedVersion(
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

    /**
     * CAS status advance with optional delivery payload fields for COMPLETED / COMMITTED.
     * {@code null} payload fields leave the stored column unchanged; non-null values replace it.
     */
    default void advanceStatusWithExpectedVersion(
            String taskId,
            long expectedVersion,
            RdTaskStatus expectedStatus,
            RdTaskStatus newStatus,
            String errorMessage,
            String executionResultJson,
            String pullRequestUrl
    ) {
        advanceStatusWithExpectedVersion(
                taskId,
                expectedVersion,
                expectedStatus,
                newStatus,
                errorMessage,
                executionResultJson,
                pullRequestUrl,
                null
        );
    }

    /**
     * CAS status advance including optional {@code promptSnapshot} for EXECUTING transitions.
     */
    default void advanceStatusWithExpectedVersion(
            String taskId,
            long expectedVersion,
            RdTaskStatus expectedStatus,
            RdTaskStatus newStatus,
            String errorMessage,
            String executionResultJson,
            String pullRequestUrl,
            String promptSnapshot
    ) {
        throw new UnsupportedOperationException("CAS status advance is unavailable for this RdTaskStore");
    }
}
