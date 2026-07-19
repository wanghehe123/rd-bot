package com.wish.rd.bootstrap.threading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.requirement.RequirementDeliveryEngine;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJob;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJobStatus;
import com.wish.rd.engine.requirement.job.RequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.model.RequirementDeliveryResult;
import com.wish.rd.engine.retry.TaskRetryCheckpointStore;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dispatches long-running requirement delivery workflows onto the dedicated
 * requirement-delivery pool.
 *
 * <p>HTTP and Feishu event adapters should enqueue via this service instead of
 * running the whole multi-agent chain on request or callback threads.
 */
@Service
public class RequirementDeliveryDispatchService {

    private static final Logger log = LoggerFactory.getLogger(RequirementDeliveryDispatchService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RequirementDeliveryEngine deliveryEngine;
    private final AsyncTaskExecutor executor;
    private final RequirementDeliveryJobStore jobStore;
    private final SnowflakeIdGenerator idGenerator;
    private final RagStreamTaskRegistry taskRegistry;
    private final String workerId;
    private final int maxAttempts;
    private final long leaseMillis;
    private final TaskScheduler heartbeatScheduler;
    private final TaskRetryCheckpointStore retryCheckpointStore;

    /**
     * Creates the requirement delivery dispatcher.
     *
     * @param deliveryEngine requirement delivery orchestrator
     * @param executor       dedicated requirement-delivery executor
     */
    public RequirementDeliveryDispatchService(
            RequirementDeliveryEngine deliveryEngine,
            @Qualifier(RdBotThreadPoolConfiguration.REQUIREMENT_DELIVERY_EXECUTOR_BEAN)
            AsyncTaskExecutor executor
    ) {
        this(
                deliveryEngine, executor, new InMemoryRequirementDeliveryJobStore(),
                SnowflakeIdGenerator.defaultGenerator(), null, "local-" + UUID.randomUUID(), 3, 600_000L, null, null);
    }

    @Autowired
    public RequirementDeliveryDispatchService(
            RequirementDeliveryEngine deliveryEngine,
            @Qualifier(RdBotThreadPoolConfiguration.REQUIREMENT_DELIVERY_EXECUTOR_BEAN)
            AsyncTaskExecutor executor,
            ObjectProvider<RequirementDeliveryJobStore> jobStoreProvider,
            ObjectProvider<SnowflakeIdGenerator> idGeneratorProvider,
            ObjectProvider<TaskScheduler> taskSchedulerProvider,
            ObjectProvider<TaskRetryCheckpointStore> retryCheckpointStoreProvider,
            RagStreamTaskRegistry taskRegistry,
            @Value("${rd.requirement-delivery.max-attempts:3}") int maxAttempts,
            @Value("${rd.requirement-delivery.lease-millis:600000}") long leaseMillis
    ) {
        this(
                deliveryEngine,
                executor,
                jobStoreProvider.getIfAvailable(InMemoryRequirementDeliveryJobStore::new),
                idGeneratorProvider.getIfAvailable(SnowflakeIdGenerator::defaultGenerator),
                taskRegistry,
                "worker-" + UUID.randomUUID(),
                maxAttempts,
                leaseMillis,
                taskSchedulerProvider.getIfAvailable(),
                retryCheckpointStoreProvider.getIfAvailable()
        );
    }

    public RequirementDeliveryDispatchService(
            RequirementDeliveryEngine deliveryEngine,
            AsyncTaskExecutor executor,
            RequirementDeliveryJobStore jobStore,
            SnowflakeIdGenerator idGenerator,
            RagStreamTaskRegistry taskRegistry,
            String workerId,
            int maxAttempts,
            long leaseMillis
    ) {
        this(deliveryEngine, executor, jobStore, idGenerator, taskRegistry, workerId,
                maxAttempts, leaseMillis, null, null);
    }

    public RequirementDeliveryDispatchService(
            RequirementDeliveryEngine deliveryEngine,
            AsyncTaskExecutor executor,
            RequirementDeliveryJobStore jobStore,
            SnowflakeIdGenerator idGenerator,
            RagStreamTaskRegistry taskRegistry,
            String workerId,
            int maxAttempts,
            long leaseMillis,
            TaskScheduler heartbeatScheduler
    ) {
        this(deliveryEngine, executor, jobStore, idGenerator, taskRegistry, workerId,
                maxAttempts, leaseMillis, heartbeatScheduler, null);
    }

    public RequirementDeliveryDispatchService(
            RequirementDeliveryEngine deliveryEngine,
            AsyncTaskExecutor executor,
            RequirementDeliveryJobStore jobStore,
            SnowflakeIdGenerator idGenerator,
            RagStreamTaskRegistry taskRegistry,
            String workerId,
            int maxAttempts,
            long leaseMillis,
            TaskScheduler heartbeatScheduler,
            TaskRetryCheckpointStore retryCheckpointStore
    ) {
        this.deliveryEngine = deliveryEngine;
        this.executor = executor;
        this.jobStore = Objects.requireNonNull(jobStore, "jobStore must not be null");
        this.idGenerator = idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator;
        this.taskRegistry = taskRegistry;
        this.workerId = workerId == null || workerId.isBlank() ? "worker-" + UUID.randomUUID() : workerId.strip();
        this.maxAttempts = Math.max(1, maxAttempts);
        this.leaseMillis = Math.max(1L, leaseMillis);
        this.heartbeatScheduler = heartbeatScheduler;
        this.retryCheckpointStore = retryCheckpointStore;
    }

    /**
     * Enqueues one requirement delivery run.
     *
     * @param taskId RD requirement task ID
     * @return future completed with the delivery result or the execution failure
     */
    public CompletableFuture<RequirementDeliveryResult> submit(String taskId) {
        long now = System.currentTimeMillis();
        jobStore.enqueue(RequirementDeliveryJob.pending(
                idGenerator.nextIdString(), taskId, maxAttempts, now));
        CompletableFuture<RequirementDeliveryResult> future = new CompletableFuture<>();
        try {
            executor.execute(() -> runClaimed(taskId, future));
        } catch (TaskRejectedException exception) {
            future.completeExceptionally(exception);
            log.warn("requirement delivery queue rejected local scheduling; persisted job remains pending, taskId={}",
                    taskId);
        }
        return future;
    }

    /** Requeues pending, retryable, and expired-lease jobs after process or pool interruption. */
    @Scheduled(fixedDelayString = "${rd.requirement-delivery.recovery-interval-millis:30000}")
    public void recover() {
        long now = System.currentTimeMillis();
        for (RequirementDeliveryJob job : jobStore.recoverable(now)) {
            if (job.isExpiredRunning(now) && job.isAttemptBudgetExhausted()) {
                jobStore.deadLetterExpiredExhausted(job.taskId(), now, "lease expired at max attempts")
                        .ifPresent(dead -> markTaskDeadLettered(dead.taskId(), dead.errorMessage()));
                continue;
            }
            try {
                executor.execute(() -> runClaimed(job.taskId(), new CompletableFuture<>()));
            } catch (TaskRejectedException exception) {
                log.warn("requirement delivery recovery queue rejected taskId={}", job.taskId());
            }
        }
    }

    private void runClaimed(String taskId, CompletableFuture<RequirementDeliveryResult> future) {
        RequirementDeliveryJob job = jobStore.claim(taskId, workerId, System.currentTimeMillis(), leaseMillis)
                .orElse(null);
        if (job == null) {
            long now = System.currentTimeMillis();
            OptionalDeadLetter dead = tryDeadLetterExhausted(taskId, now);
            if (dead.present()) {
                future.completeExceptionally(new IllegalStateException(
                        "requirement delivery job dead-lettered after lease expiry: " + taskId));
                return;
            }
            future.completeExceptionally(new IllegalStateException(
                    "requirement delivery job is already claimed or terminal: " + taskId));
            return;
        }
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeat = null;
        try {
            jobStore.heartbeat(job.jobId(), workerId, System.currentTimeMillis(), leaseMillis);
            heartbeat = scheduleHeartbeat(job, leaseLost);
            if (leaseLost.get()) {
                throw new IllegalStateException("requirement delivery lease lost before execution: " + taskId);
            }
            RequirementDeliveryResult result = deliveryEngine.submit(taskId);
            cancelHeartbeat(heartbeat);
            if (leaseLost.get()) {
                throw new IllegalStateException("requirement delivery lease lost during execution: " + taskId);
            }
            persistDeliveryOutcome(job, result);
            completeRetryCheckpoint(taskId, result.status(), result.errorMessage());
            future.complete(result);
        } catch (RuntimeException exception) {
            cancelHeartbeat(heartbeat);
            markTaskRetryableAfterException(taskId, exception);
            try {
                if (!leaseLost.get()) {
                    RequirementDeliveryJob failed = jobStore.fail(
                            job.jobId(), workerId, safeError(exception), System.currentTimeMillis());
                    if (failed.status() == RequirementDeliveryJobStatus.DEAD_LETTERED) {
                        markTaskDeadLettered(taskId, failed.errorMessage());
                    }
                }
            } catch (RuntimeException leaseException) {
                log.warn("requirement delivery fail after lease loss, taskId={}, reason={}",
                        taskId, safeError(leaseException));
            }
            log.error("requirement delivery failed in isolated thread pool, taskId={}", taskId, exception);
            completeRetryCheckpoint(taskId, RdTaskStatus.FAILED_RETRYABLE, safeError(exception));
            future.completeExceptionally(exception);
        }
    }

    private void persistDeliveryOutcome(RequirementDeliveryJob job, RequirementDeliveryResult result) {
        RdTaskStatus status = result.status();
        if (status == RdTaskStatus.COMPLETED
                || status == RdTaskStatus.COMMITTED
                || status == RdTaskStatus.MERGED
                || status == RdTaskStatus.REPORTING
                || status == RdTaskStatus.WAITING_APPROVAL
                || status == RdTaskStatus.WAITING_POLICY) {
            jobStore.complete(job.jobId(), workerId, System.currentTimeMillis());
            return;
        }
        String reason = result.errorMessage().isBlank()
                ? "requirement delivery ended as " + status
                : result.errorMessage();
        long now = System.currentTimeMillis();
        if (status == RdTaskStatus.FAILED_RETRYABLE) {
            RequirementDeliveryJob failed = jobStore.fail(job.jobId(), workerId, reason, now);
            if (failed.status() == RequirementDeliveryJobStatus.DEAD_LETTERED) {
                markTaskDeadLettered(job.taskId(), failed.errorMessage());
            }
            return;
        }
        // REJECTED / FAILED_NEEDS_HUMAN / CANCELLED must not look like SUCCEEDED or auto-retry.
        jobStore.cancelByTask(job.taskId(), reason, now);
    }

    private ScheduledFuture<?> scheduleHeartbeat(RequirementDeliveryJob job, AtomicBoolean leaseLost) {
        if (heartbeatScheduler == null) {
            return null;
        }
        long intervalMillis = Math.max(1_000L, leaseMillis / 3L);
        return heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                jobStore.heartbeat(job.jobId(), workerId, System.currentTimeMillis(), leaseMillis);
            } catch (RuntimeException exception) {
                leaseLost.set(true);
                log.warn("requirement delivery heartbeat failed, taskId={}, reason={}",
                        job.taskId(), safeError(exception));
            }
        }, Duration.ofMillis(intervalMillis));
    }

    private void completeRetryCheckpoint(String taskId, RdTaskStatus taskStatus, String reason) {
        if (retryCheckpointStore == null) {
            return;
        }
        TaskRetryCheckpoint checkpoint = retryCheckpointStore.findActiveByTask(taskId).orElse(null);
        if (checkpoint == null || checkpoint.status() != TaskRetryCheckpointStatus.DISPATCHED) {
            return;
        }
        TaskRetryCheckpointStatus target;
        if (taskStatus == RdTaskStatus.COMPLETED || taskStatus == RdTaskStatus.COMMITTED
                || taskStatus == RdTaskStatus.MERGED || taskStatus == RdTaskStatus.REPORTING
                || taskStatus == RdTaskStatus.WAITING_APPROVAL || taskStatus == RdTaskStatus.WAITING_POLICY) {
            target = TaskRetryCheckpointStatus.SUCCEEDED;
        } else if (taskStatus == RdTaskStatus.FAILED_RETRYABLE) {
            target = TaskRetryCheckpointStatus.FAILED_RETRYABLE;
        } else if (taskStatus == RdTaskStatus.CANCELLED) {
            target = TaskRetryCheckpointStatus.CANCELLED;
        } else {
            target = TaskRetryCheckpointStatus.FAILED_NEEDS_HUMAN;
        }
        try {
            retryCheckpointStore.transition(checkpoint.checkpointId(), checkpoint.status(), target,
                    reason == null ? "" : reason, System.currentTimeMillis());
        } catch (RuntimeException exception) {
            log.warn("retry checkpoint outcome sync failed, taskId={}, checkpointId={}, reason={}",
                    taskId, checkpoint.checkpointId(), safeError(exception));
        }
    }

    private void markTaskRetryableAfterException(String taskId, RuntimeException exception) {
        if (taskRegistry == null) {
            return;
        }
        try {
            RdTask current = taskRegistry.getTask(taskId);
            if (!(current instanceof RdRequirementTask requirementTask)
                    || requirementTask.status() == RdTaskStatus.FAILED_RETRYABLE
                    || requirementTask.status() == RdTaskStatus.FAILED_NEEDS_HUMAN
                    || requirementTask.status() == RdTaskStatus.REJECTED
                    || requirementTask.status() == RdTaskStatus.CANCELLED
                    || requirementTask.status() == RdTaskStatus.DEAD_LETTERED) {
                return;
            }
            TaskFailurePhase phase = failurePhase(requirementTask.status());
            var result = OBJECT_MAPPER.createObjectNode();
            result.put("failurePhase", phase.name());
            result.put("errorCategory", exception.getClass().getSimpleName());
            result.put("errorMessage", safeError(exception));
            taskRegistry.markRequirementFailedRetryable(taskId, safeError(exception), result.toString());
        } catch (RuntimeException stateException) {
            log.warn("failed to persist structured requirement failure, taskId={}, reason={}",
                    taskId, safeError(stateException));
        }
    }

    private TaskFailurePhase failurePhase(RdTaskStatus status) {
        return switch (status) {
            case CREATED, MATERIAL_COLLECTING -> TaskFailurePhase.MATERIAL;
            case MATERIAL_READY, CONTEXT_BUILDING -> TaskFailurePhase.CONTEXT;
            case CONTEXT_READY, PLAN_GENERATING -> TaskFailurePhase.PLAN;
            case PLAN_GENERATED, WAITING_POLICY, WAITING_APPROVAL -> TaskFailurePhase.POLICY;
            case VALIDATING -> TaskFailurePhase.DETERMINISTIC_REVIEW;
            case PR_CREATING -> TaskFailurePhase.PR_PUBLICATION;
            case EXECUTING -> TaskFailurePhase.AGENT_ROLE;
            default -> TaskFailurePhase.CONTEXT;
        };
    }

    private void cancelHeartbeat(ScheduledFuture<?> heartbeat) {
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
    }

    private OptionalDeadLetter tryDeadLetterExhausted(String taskId, long now) {
        return new OptionalDeadLetter(jobStore.deadLetterExpiredExhausted(
                taskId, now, "lease expired at max attempts")
                .map(dead -> {
                    markTaskDeadLettered(dead.taskId(), dead.errorMessage());
                    return dead;
                })
                .isPresent());
    }

    private void markTaskDeadLettered(String taskId, String reason) {
        if (taskRegistry == null) {
            return;
        }
        try {
            taskRegistry.markDeadLettered(taskId, reason);
        } catch (RuntimeException exception) {
            log.warn("requirement delivery dead-letter task sync failed, taskId={}, reason={}",
                    taskId, safeError(exception));
        }
    }

    private String safeError(RuntimeException exception) {
        String message = exception == null || exception.getMessage() == null
                ? "requirement delivery failed"
                : exception.getMessage();
        return message.length() <= 2_000 ? message : message.substring(0, 2_000);
    }

    private record OptionalDeadLetter(boolean present) {
    }
}
