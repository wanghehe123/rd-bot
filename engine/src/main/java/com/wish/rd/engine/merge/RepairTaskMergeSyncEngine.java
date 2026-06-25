package com.wish.rd.engine.merge;

import com.wish.rd.engine.ticket.RepairRecordRepository;
import com.wish.rd.engine.ticket.RepairRecordStatus;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.RdBugFixTask;
import com.wish.rd.rag.runtime.RdTaskStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 修复任务合并状态同步编排。
 *
 * <p>该 engine 读取 RD 任务状态机中的 {@code COMMITTED} 任务，查询外部代码平台 PR
 * 状态，并在 PR 已合并时推进为 {@code MERGED}。
 */
@Service
public class RepairTaskMergeSyncEngine {

    private final RagStreamTaskRegistry taskRegistry;
    private final PullRequestMergeStatusPort pullRequestStatusPort;
    private final RepairRecordRepository repairRecordRepository;

    /**
     * 创建 PR 合并状态同步引擎。
     *
     * @param taskRegistry          RD 任务状态机
     * @param pullRequestStatusPort PR 状态查询端口
     */
    @Autowired
    public RepairTaskMergeSyncEngine(
            RagStreamTaskRegistry taskRegistry,
            PullRequestMergeStatusPort pullRequestStatusPort,
            ObjectProvider<RepairRecordRepository> repairRecordRepository
    ) {
        this(taskRegistry, pullRequestStatusPort, repairRecordRepository.getIfAvailable());
    }

    public RepairTaskMergeSyncEngine(
            RagStreamTaskRegistry taskRegistry,
            PullRequestMergeStatusPort pullRequestStatusPort
    ) {
        this(taskRegistry, pullRequestStatusPort, (RepairRecordRepository) null);
    }

    public RepairTaskMergeSyncEngine(
            RagStreamTaskRegistry taskRegistry,
            PullRequestMergeStatusPort pullRequestStatusPort,
            RepairRecordRepository repairRecordRepository
    ) {
        this.taskRegistry = Objects.requireNonNull(taskRegistry, "taskRegistry must not be null");
        this.pullRequestStatusPort = Objects.requireNonNull(
                pullRequestStatusPort,
                "pullRequestStatusPort must not be null"
        );
        this.repairRecordRepository = repairRecordRepository;
    }

    /**
     * 同步单个任务的 PR 合并状态。
     *
     * @param taskId 任务 ID
     * @return 同步后的任务快照
     */
    public RdBugFixTask syncTask(String taskId) {
        RdBugFixTask task = taskRegistry.get(taskId);
        if (task.status() == RdTaskStatus.MERGED) {
            syncRepairRecord(task, RepairRecordStatus.MERGED, "pull request merged: " + task.pullRequestUrl());
            return task;
        }
        if (task.status() != RdTaskStatus.COMMITTED || task.pullRequestUrl().isBlank()) {
            return task;
        }
        syncRepairRecord(task, RepairRecordStatus.COMMITTED, "auto repair committed: " + task.pullRequestUrl());
        PullRequestMergeStatus status = pullRequestStatusPort.findByUrl(task.pullRequestUrl());
        if (!status.merged()) {
            return task;
        }
        RdBugFixTask merged = taskRegistry.markMerged(task.taskId());
        syncRepairRecord(merged, RepairRecordStatus.MERGED, "pull request merged: " + status.pullRequestUrl());
        return merged;
    }

    /**
     * 同步所有处于 COMMITTED 状态的任务，并修复已 MERGED 任务对应 repair record 的滞后状态。
     *
     * @return 被检查并返回的任务快照列表
     */
    public List<RdBugFixTask> syncAllCommitted() {
        return taskRegistry.listBugFixTasks().stream()
                .filter(task -> task.status() == RdTaskStatus.COMMITTED || task.status() == RdTaskStatus.MERGED)
                .map(task -> syncTask(task.taskId()))
                .toList();
    }

    private void syncRepairRecord(RdBugFixTask task, RepairRecordStatus status, String summary) {
        if (repairRecordRepository == null || task == null || task.ticketId().isBlank()) {
            return;
        }
        repairRecordRepository.findByTicketId(task.ticketId())
                .ifPresent(record -> repairRecordRepository.updateStatus(record.id(), status, summary));
    }
}
