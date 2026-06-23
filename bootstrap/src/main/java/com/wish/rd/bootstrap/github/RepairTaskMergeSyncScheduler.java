package com.wish.rd.bootstrap.github;

import com.wish.rd.engine.merge.RepairTaskMergeSyncEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 后台同步已提交 PR 的 merge 状态。
 */
@Component
@ConditionalOnProperty(prefix = "rd.github.merge-sync", name = "enabled", havingValue = "true")
public class RepairTaskMergeSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(RepairTaskMergeSyncScheduler.class);

    private final RepairTaskMergeSyncEngine mergeSyncEngine;

    /**
     * 创建 PR 合并状态同步调度器。
     *
     * @param mergeSyncEngine 合并状态同步编排
     */
    public RepairTaskMergeSyncScheduler(RepairTaskMergeSyncEngine mergeSyncEngine) {
        this.mergeSyncEngine = mergeSyncEngine;
    }

    /**
     * 轮询所有 COMMITTED 任务并同步 PR merge 状态。
     */
    @Scheduled(fixedDelayString = "${rd.github.merge-sync.poll-interval-millis:30000}")
    public void syncCommittedTasks() {
        try {
            mergeSyncEngine.syncAllCommitted();
        } catch (RuntimeException ex) {
            log.warn("Failed to sync repair task merge status", ex);
        }
    }
}
