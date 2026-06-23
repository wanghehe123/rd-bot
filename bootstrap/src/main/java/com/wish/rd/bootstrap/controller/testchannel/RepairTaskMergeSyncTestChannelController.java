package com.wish.rd.bootstrap.controller.testchannel;

import com.wish.rd.engine.merge.RepairTaskMergeSyncEngine;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.RdBugFixTask;
import com.wish.rd.rag.runtime.RdTaskStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/**
 * PR 合并状态同步测试通道（仅 {@code /test/...} 前缀）。
 *
 * <p>该入口用于把真实 GitHub PR 绑定到本地 RD 任务状态机，方便验收 PR merge 后
 * 任务是否推进到 {@code MERGED}。
 */
@RestController
public class RepairTaskMergeSyncTestChannelController {

    private final RagStreamTaskRegistry taskRegistry;
    private final RepairTaskMergeSyncEngine mergeSyncEngine;

    public RepairTaskMergeSyncTestChannelController(
            RagStreamTaskRegistry taskRegistry,
            RepairTaskMergeSyncEngine mergeSyncEngine
    ) {
        this.taskRegistry = taskRegistry;
        this.mergeSyncEngine = mergeSyncEngine;
    }

    /**
     * 将已有 PR 以 COMMITTED 状态导入本地任务状态机。
     *
     * @param taskId  任务 ID
     * @param request 导入请求
     * @return 导入后的任务快照
     */
    @PostMapping("/test/repair/bugfix/tasks/{taskId}/committed")
    public ResponseEntity<RdBugFixTask> importCommitted(
            @PathVariable("taskId") String taskId,
            @RequestBody ImportCommittedRequest request
    ) {
        ensureCommitted(taskId, request);
        return ResponseEntity.ok(taskRegistry.get(taskId));
    }

    /**
     * 手动触发单个任务的 PR merge 状态同步。
     *
     * @param taskId 任务 ID
     * @return 同步后的任务快照
     */
    @PostMapping("/test/repair/bugfix/tasks/{taskId}/sync-merge")
    public ResponseEntity<RdBugFixTask> syncMerge(@PathVariable("taskId") String taskId) {
        return ResponseEntity.ok(mergeSyncEngine.syncTask(taskId));
    }

    private void ensureCommitted(String taskId, ImportCommittedRequest request) {
        RdBugFixTask task = findOrRegister(taskId);
        if (task.status() == RdTaskStatus.MERGED || task.status() == RdTaskStatus.REJECTED) {
            return;
        }
        if (task.status() == RdTaskStatus.CREATED) {
            task = taskRegistry.markSearching(taskId, "");
        }
        if (task.status() == RdTaskStatus.SEARCHING) {
            task = taskRegistry.markExecuting(taskId, "");
        }
        if (task.status() == RdTaskStatus.EXECUTING || task.status() == RdTaskStatus.COMMITTED) {
            taskRegistry.markCommitted(taskId, request.pullRequestUrl(), request.executionResultJson());
        }
    }

    private RdBugFixTask findOrRegister(String taskId) {
        try {
            return taskRegistry.get(taskId);
        } catch (NoSuchElementException ex) {
            return taskRegistry.registerRunning(taskId, "");
        }
    }

    /** 已提交 PR 的导入请求。 */
    public record ImportCommittedRequest(
            String ticketId,
            String title,
            String priority,
            String pullRequestUrl,
            String executionResultJson
    ) {

        public ImportCommittedRequest {
            ticketId = ticketId == null ? "" : ticketId.strip();
            title = title == null ? "" : title;
            priority = priority == null || priority.isBlank() ? "P2" : priority.strip().toUpperCase();
            if (pullRequestUrl == null || pullRequestUrl.isBlank()) {
                throw new IllegalArgumentException("pullRequestUrl must not be blank");
            }
            pullRequestUrl = pullRequestUrl.strip();
            executionResultJson = executionResultJson == null || executionResultJson.isBlank()
                    ? "{}"
                    : executionResultJson;
        }
    }
}
