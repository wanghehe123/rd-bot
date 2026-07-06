package com.wish.rd.bootstrap.github;

import com.wish.rd.engine.merge.RepairTaskMergeSyncEngine;
import com.wish.rd.bootstrap.threading.RdBotThreadPoolConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台同步已提交 PR 的 merge 状态。
 */
@Component
@ConditionalOnProperty(prefix = "rd.github.merge-sync", name = "enabled", havingValue = "true")
public class RepairTaskMergeSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(RepairTaskMergeSyncScheduler.class);

    private final RepairTaskMergeSyncEngine mergeSyncEngine;
    private final AsyncTaskExecutor maintenanceExecutor;
    private final AtomicBoolean syncRunning = new AtomicBoolean(false);

    /**
     * 创建 PR 合并状态同步调度器。
     *
     * @param mergeSyncEngine 合并状态同步编排
     */
    public RepairTaskMergeSyncScheduler(RepairTaskMergeSyncEngine mergeSyncEngine) {
        this(mergeSyncEngine, null);
    }

    /**
     * 创建 PR 合并状态同步调度器。
     *
     * @param mergeSyncEngine     合并状态同步编排
     * @param maintenanceExecutor 维护任务线程池
     */
    @Autowired
    public RepairTaskMergeSyncScheduler(
            RepairTaskMergeSyncEngine mergeSyncEngine,
            @Qualifier(RdBotThreadPoolConfiguration.MAINTENANCE_EXECUTOR_BEAN)
            AsyncTaskExecutor maintenanceExecutor
    ) {
        this.mergeSyncEngine = mergeSyncEngine;
        this.maintenanceExecutor = maintenanceExecutor;
    }

    /**
     * 轮询所有 COMMITTED 任务并同步 PR merge 状态。
     */
    @Scheduled(fixedDelayString = "${rd.github.merge-sync.poll-interval-millis:30000}")
    public void syncCommittedTasks() {
        if (maintenanceExecutor != null) {
            submitIsolatedSync();
            return;
        }
        syncCommittedTasksNow();
    }

    private void submitIsolatedSync() {
        if (!syncRunning.compareAndSet(false, true)) {
            log.info("previous repair task merge sync still running, skip this tick");
            return;
        }
        try {
            maintenanceExecutor.execute(() -> {
                try {
                    syncCommittedTasksNow();
                } finally {
                    syncRunning.set(false);
                }
            });
        } catch (TaskRejectedException exception) {
            syncRunning.set(false);
            log.warn("repair task merge sync rejected by maintenance executor", exception);
        }
    }

    private void syncCommittedTasksNow() {
        try {
            mergeSyncEngine.syncAllCommitted();
        } catch (RuntimeException ex) {
            log.warn("Failed to sync repair task merge status", ex);
        }
    }
}
