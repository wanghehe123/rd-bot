package com.wish.rd.engine.merge;

import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.RdBugFixTask;
import com.wish.rd.rag.runtime.RdTaskStatus;
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

    /**
     * 创建 PR 合并状态同步引擎。
     *
     * @param taskRegistry          RD 任务状态机
     * @param pullRequestStatusPort PR 状态查询端口
     */
    public RepairTaskMergeSyncEngine(
            RagStreamTaskRegistry taskRegistry,
            PullRequestMergeStatusPort pullRequestStatusPort
    ) {
        this.taskRegistry = Objects.requireNonNull(taskRegistry, "taskRegistry must not be null");
        this.pullRequestStatusPort = Objects.requireNonNull(
                pullRequestStatusPort,
                "pullRequestStatusPort must not be null"
        );
    }

    /**
     * 同步单个任务的 PR 合并状态。
     *
     * @param taskId 任务 ID
     * @return 同步后的任务快照
     */
    public RdBugFixTask syncTask(String taskId) {
        RdBugFixTask task = taskRegistry.get(taskId);
        if (task.status() != RdTaskStatus.COMMITTED || task.pullRequestUrl().isBlank()) {
            return task;
        }
        PullRequestMergeStatus status = pullRequestStatusPort.findByUrl(task.pullRequestUrl());
        if (!status.merged()) {
            return task;
        }
        return taskRegistry.markMerged(task.taskId());
    }

    /**
     * 同步所有处于 COMMITTED 状态的任务。
     *
     * @return 被检查并返回的任务快照列表
     */
    public List<RdBugFixTask> syncAllCommitted() {
        return taskRegistry.listBugFixTasks().stream()
                .filter(task -> task.status() == RdTaskStatus.COMMITTED)
                .map(task -> syncTask(task.taskId()))
                .toList();
    }
}
