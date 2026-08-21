package com.wish.rd.engine.merge;

import com.wish.rd.engine.merge.model.PullRequestMergeStatus;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * RD 任务合并状态同步编排。
 *
 * <p>该 engine 读取 RD 任务状态机中已发布 PR 的任务，查询外部代码平台 PR
 * 状态，并在 PR 已合并时推进为 {@code MERGED}。
 */
@Service
public class RepairTaskMergeSyncEngine {

    private static final Logger log = LoggerFactory.getLogger(RepairTaskMergeSyncEngine.class);

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
    public RdTask syncTask(String taskId) {
        RdTask task = taskRegistry.getTask(taskId);
        if (task.status() == RdTaskStatus.MERGED) {
            return task;
        }
        if (!shouldCheckPullRequest(task)) {
            return task;
        }
        PullRequestMergeStatus status = pullRequestStatusPort.findByUrl(pullRequestUrl(task));
        if (!status.merged()) {
            return task;
        }
        return markMerged(task);
    }

    /**
     * 同步所有已发布 PR 的任务。
     *
     * @return 被检查并返回的任务快照列表
     */
    public List<RdTask> syncAllCommitted() {
        List<RdTask> synced = new ArrayList<>();
        for (RdTask task : taskRegistry.listTasks()) {
            if (!shouldCheckPullRequest(task) && task.status() != RdTaskStatus.MERGED) {
                continue;
            }
            try {
                synced.add(syncTask(task.taskId()));
            } catch (RuntimeException exception) {
                log.warn(
                        "skipped repair task merge status sync, taskId={}, pullRequestUrl={}, reason={}",
                        task.taskId(),
                        pullRequestUrl(task),
                        exception.getMessage()
                );
                synced.add(taskRegistry.getTask(task.taskId()));
            }
        }
        return List.copyOf(synced);
    }

    private boolean shouldCheckPullRequest(RdTask task) {
        return (task.status() == RdTaskStatus.COMMITTED || task.status() == RdTaskStatus.COMPLETED)
                && !pullRequestUrl(task).isBlank();
    }

    private RdTask markMerged(RdTask task) {
        if (task instanceof RdRequirementTask) {
            return taskRegistry.markRequirementMerged(task.taskId());
        }
        return taskRegistry.markMerged(task.taskId());
    }

    private String pullRequestUrl(RdTask task) {
        if (task instanceof RdBugFixTask bugFixTask) {
            return bugFixTask.pullRequestUrl();
        }
        if (task instanceof RdRequirementTask requirementTask) {
            return requirementTask.pullRequestUrl();
        }
        return "";
    }
}
