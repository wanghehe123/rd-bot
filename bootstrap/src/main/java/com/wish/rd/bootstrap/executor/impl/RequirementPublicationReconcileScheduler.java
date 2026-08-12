package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.bootstrap.threading.RdBotThreadPoolConfiguration;
import com.wish.rd.engine.requirement.publication.RequirementPublicationContinuationPort;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconciliationService;
import com.wish.rd.engine.requirement.publication.RequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.model.RequirementPublication;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationContinuation;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationStatus;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Polls due {@code UNKNOWN_REMOTE_RESULT} publications and advances the ledger
 * when remote branch/PR evidence appears (without replaying push/PR writes).
 */
@Component
@ConditionalOnBean({RequirementPublicationStore.class, RequirementPublicationReconciliationService.class})
@ConditionalOnProperty(
        prefix = "rd.requirement-publication.reconcile",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class RequirementPublicationReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(RequirementPublicationReconcileScheduler.class);
    private static final int DEFAULT_BATCH_SIZE = 20;

    private final RequirementPublicationStore publicationStore;
    private final RequirementPublicationReconciliationService reconciliationService;
    private final RagStreamTaskRegistry taskRegistry;
    private final AsyncTaskExecutor maintenanceExecutor;
    private final RequirementPublicationContinuationPort continuationPort;
    private final LongSupplier clock;
    private final AtomicBoolean syncRunning = new AtomicBoolean(false);

    public RequirementPublicationReconcileScheduler(
            RequirementPublicationStore publicationStore,
            RequirementPublicationReconciliationService reconciliationService,
            RagStreamTaskRegistry taskRegistry
    ) {
        this(publicationStore, reconciliationService, taskRegistry, null, null, System::currentTimeMillis);
    }

    @Autowired
    public RequirementPublicationReconcileScheduler(
            RequirementPublicationStore publicationStore,
            RequirementPublicationReconciliationService reconciliationService,
            RagStreamTaskRegistry taskRegistry,
            @Qualifier(RdBotThreadPoolConfiguration.MAINTENANCE_EXECUTOR_BEAN)
            AsyncTaskExecutor maintenanceExecutor,
            RequirementPublicationContinuationPort continuationPort
    ) {
        this(publicationStore, reconciliationService, taskRegistry, maintenanceExecutor, continuationPort,
                System::currentTimeMillis);
    }

    public RequirementPublicationReconcileScheduler(
            RequirementPublicationStore publicationStore,
            RequirementPublicationReconciliationService reconciliationService,
            RagStreamTaskRegistry taskRegistry,
            AsyncTaskExecutor maintenanceExecutor,
            LongSupplier clock
    ) {
        this(publicationStore, reconciliationService, taskRegistry, maintenanceExecutor, null, clock);
    }

    public RequirementPublicationReconcileScheduler(
            RequirementPublicationStore publicationStore,
            RequirementPublicationReconciliationService reconciliationService,
            RagStreamTaskRegistry taskRegistry,
            AsyncTaskExecutor maintenanceExecutor,
            RequirementPublicationContinuationPort continuationPort,
            LongSupplier clock
    ) {
        this.publicationStore = Objects.requireNonNull(publicationStore, "publicationStore must not be null");
        this.reconciliationService = Objects.requireNonNull(
                reconciliationService, "reconciliationService must not be null");
        this.taskRegistry = Objects.requireNonNull(taskRegistry, "taskRegistry must not be null");
        this.maintenanceExecutor = maintenanceExecutor;
        this.continuationPort = Objects.requireNonNull(
                continuationPort, "continuationPort must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Scheduled(fixedDelayString = "${rd.requirement-publication.reconcile.poll-interval-millis:30000}")
    public void reconcileDuePublications() {
        if (maintenanceExecutor != null) {
            submitIsolatedSync();
            return;
        }
        reconcileDuePublicationsNow();
    }

    private void submitIsolatedSync() {
        if (!syncRunning.compareAndSet(false, true)) {
            log.info("previous publication reconcile still running, skip this tick");
            return;
        }
        try {
            maintenanceExecutor.execute(() -> {
                try {
                    reconcileDuePublicationsNow();
                } finally {
                    syncRunning.set(false);
                }
            });
        } catch (TaskRejectedException exception) {
            syncRunning.set(false);
            log.warn("publication reconcile rejected by maintenance executor", exception);
        }
    }

    void reconcileDuePublicationsNow() {
        try {
            long now = Math.max(0L, clock.getAsLong());
            List<RequirementPublication> due = publicationStore.findDueForReconcile(now, DEFAULT_BATCH_SIZE);
            for (RequirementPublication publication : due) {
                reconcileOne(publication);
            }
        } catch (RuntimeException exception) {
            log.warn("Failed to reconcile due requirement publications", exception);
        }
    }

    private void reconcileOne(RequirementPublication publication) {
        try {
            RdRequirementTask task = taskRegistry.getRequirementTask(publication.taskId());
            reconciliationService.reconcileUnknown(
                    publication.operationId(),
                    task.taskId(),
                    task.repoOwner(),
                    task.repoName()
            );
            RequirementPublication after = publicationStore.findByOperationId(publication.operationId()).orElse(null);
            if (after != null && shouldContinue(after)) {
                continuationPort.enqueue(new RequirementPublicationContinuation(
                        after.operationId(),
                        task.taskId(),
                        task.version(),
                        task.fencingToken(),
                        task.projectId(),
                        task.priority()
                ));
            }
            if (after != null && after.status() == RequirementPublicationStatus.UNKNOWN_REMOTE_RESULT) {
                reconciliationService.deferIfStillUnknown(publication.operationId());
            }
        } catch (RuntimeException exception) {
            log.warn("Failed to reconcile publication {}", publication.operationId(), exception);
            try {
                reconciliationService.deferIfStillUnknown(publication.operationId());
            } catch (RuntimeException ignored) {
                // Keep the row due; next tick will retry.
            }
        }
    }

    private static boolean shouldContinue(RequirementPublication publication) {
        return publication.status() == RequirementPublicationStatus.BRANCH_CONFIRMED
                || publication.status() == RequirementPublicationStatus.PR_CONFIRMED;
    }
}
