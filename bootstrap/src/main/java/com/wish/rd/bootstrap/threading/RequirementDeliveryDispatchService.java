package com.wish.rd.bootstrap.threading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.requirement.RequirementDeliveryEngine;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.job.RequirementStageExecutor;
import com.wish.rd.engine.requirement.job.RequirementStageFinalizationPort;
import com.wish.rd.engine.requirement.policy.RequirementPolicyTransactionPort;
import com.wish.rd.engine.requirement.policy.RequirementPolicyRunStore;
import com.wish.rd.engine.requirement.policy.model.RecordRequirementPolicyDecisionCommand;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApplyDisposition;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApplyResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationProposal;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationPreparationResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyResumeResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRunState;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageFinalizationPort;
import com.wish.rd.engine.requirement.job.model.CommandDisposition;
import com.wish.rd.engine.requirement.job.model.ContinuationSpec;
import com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJob;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJobStatus;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.job.model.RequirementStageFinalization;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.requirement.job.model.RequirementTaskMutation;
import com.wish.rd.engine.requirement.job.RequirementStageFinalizationPort.ExhaustionProvenanceDraft;
import com.wish.rd.bootstrap.threading.impl.EngineRequirementStageExecutor;
import com.wish.rd.engine.requirement.job.RequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.RequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.model.RequirementDeliveryResult;
import com.wish.rd.engine.retry.TaskRetryAttemptBindingStore;
import com.wish.rd.engine.retry.TaskRetryCheckpointStore;
import com.wish.rd.engine.retry.model.TaskRetryAttemptKind;
import com.wish.rd.engine.retry.TaskRetryExhaustionProvenanceFactory;
import com.wish.rd.engine.requirement.publication.RequirementOperationId;
import com.wish.rd.engine.requirement.publication.RequirementPublicationIntentFactory;
import com.wish.rd.engine.requirement.publication.RequirementPublicationStore;
import com.wish.rd.engine.retry.TaskRetryRoutePlanner;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.scheduling.FairRequirementDeliveryClaimPlanner;
import com.wish.rd.engine.scheduling.RequirementDeliveryMetrics;
import com.wish.rd.engine.scheduling.model.RequirementDeliverySchedulingPolicy;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.project.agent.AgentExecutionProfileService;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
    private final TaskRetryAttemptBindingStore attemptBindingStore;
    private final TaskRetryExhaustionProvenanceFactory exhaustionProvenanceFactory;
    private final RequirementStageCommandStore stageCommandStore;
    private final RequirementStageExecutor stageExecutor;
    private final RequirementStageFinalizationPort stageFinalizationPort;
    private final FairRequirementDeliveryClaimPlanner claimPlanner = new FairRequirementDeliveryClaimPlanner();
    private final RequirementDeliverySchedulingPolicy schedulingPolicy;
    private final AgentExecutionProfileService executionProfileService;
    private final RequirementStageCommandFactory stageCommandFactory;
    private final RequirementDeliveryMetrics metrics = new RequirementDeliveryMetrics();
    /**
     * Optional durable policy control-plane boundary. It is setter-injected so existing
     * dispatcher constructors remain source compatible in memory-only test/runtime profiles.
     */
    private RequirementPolicyTransactionPort requirementPolicyTransactionPort;
    /** Durable policy generations used by the dedicated evaluate control command. */
    private RequirementPolicyRunStore requirementPolicyRunStore;
    /** Publication ledger used to resolve exhaustion provenance for bare PUBLICATION commands. */
    private RequirementPublicationStore requirementPublicationStore;
    /**
     * Futures owned by this dispatcher while their durable command is still pending. The map is
     * deliberately keyed by command identity so a later recovery tick can hand the same future
     * to the worker that eventually claims the command. A terminal dead-letter also removes the
     * association after completing it exceptionally because no future claim is possible.
     */
    private final ConcurrentMap<String, CompletableFuture<RequirementDeliveryResult>> pendingFutures =
            new ConcurrentHashMap<>();
    /**
     * Holds the execute() result when a legacy execute-only stage adapter is bridged onto
     * {@code plan()}. Cleared after the durable finalizer consumes the mirrored plan.
     */
    private final ThreadLocal<RequirementDeliveryResult> bridgedLegacyResults = new ThreadLocal<>();

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
                SnowflakeIdGenerator.defaultGenerator(), null, "local-" + UUID.randomUUID(), 3, 600_000L,
                null, null, new InMemoryRequirementStageCommandStore(), null);
    }

    @Autowired
    public RequirementDeliveryDispatchService(
            RequirementDeliveryEngine deliveryEngine,
            @Qualifier(RdBotThreadPoolConfiguration.REQUIREMENT_DELIVERY_EXECUTOR_BEAN)
            AsyncTaskExecutor executor,
            ObjectProvider<RequirementDeliveryJobStore> jobStoreProvider,
            ObjectProvider<SnowflakeIdGenerator> idGeneratorProvider,
            @Qualifier("taskScheduler") ObjectProvider<TaskScheduler> taskSchedulerProvider,
            ObjectProvider<TaskRetryCheckpointStore> retryCheckpointStoreProvider,
            ObjectProvider<TaskRetryAttemptBindingStore> attemptBindingStoreProvider,
            RagStreamTaskRegistry taskRegistry,
            ObjectProvider<RequirementStageCommandStore> stageCommandStoreProvider,
            ObjectProvider<RequirementStageExecutor> stageExecutorProvider,
            ObjectProvider<RequirementStageFinalizationPort> stageFinalizationPortProvider,
            ObjectProvider<RequirementDeliverySchedulingProperties> schedulingPropertiesProvider,
            ObjectProvider<AgentExecutionProfileService> executionProfileServiceProvider,
            @Value("${rd.knowledge.store:memory}") String knowledgeStore,
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
                retryCheckpointStoreProvider.getIfAvailable(),
                resolveStageCommandStore(stageCommandStoreProvider, knowledgeStore),
                stageExecutorProvider.getIfAvailable(() -> command -> deliveryEngine.executeStage(command)),
                stageFinalizationPortProvider.getIfAvailable(),
                resolveSchedulingPolicy(schedulingPropertiesProvider),
                executionProfileServiceProvider.getIfAvailable(),
                attemptBindingStoreProvider.getIfAvailable()
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
                maxAttempts, leaseMillis, null, null, null, null);
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
                maxAttempts, leaseMillis, heartbeatScheduler, null, null, null);
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
        this(deliveryEngine, executor, jobStore, idGenerator, taskRegistry, workerId,
                maxAttempts, leaseMillis, heartbeatScheduler, retryCheckpointStore, null, null);
    }

    /** Creates a dispatcher with an explicit durable stage-command store. */
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
            TaskRetryCheckpointStore retryCheckpointStore,
            RequirementStageCommandStore stageCommandStore
    ) {
        this(deliveryEngine, executor, jobStore, idGenerator, taskRegistry, workerId,
                maxAttempts, leaseMillis, heartbeatScheduler, retryCheckpointStore,
                stageCommandStore, null);
    }

    /** Creates a dispatcher with an explicit durable stage-command store and stage executor. */
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
            TaskRetryCheckpointStore retryCheckpointStore,
            RequirementStageCommandStore stageCommandStore,
            RequirementStageExecutor stageExecutor
    ) {
        this(deliveryEngine, executor, jobStore, idGenerator, taskRegistry, workerId,
                maxAttempts, leaseMillis, heartbeatScheduler, retryCheckpointStore, stageCommandStore,
                stageExecutor, null, RequirementDeliverySchedulingPolicy.defaults(), null);
    }

    /**
     * Creates a dispatcher with explicit scheduling policy and authoritative execution-profile
     * resolver. This constructor is used by focused policy tests and the Spring production path.
     */
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
            TaskRetryCheckpointStore retryCheckpointStore,
            RequirementStageCommandStore stageCommandStore,
            RequirementStageExecutor stageExecutor,
            RequirementDeliverySchedulingPolicy schedulingPolicy,
            AgentExecutionProfileService executionProfileService
    ) {
        this(deliveryEngine, executor, jobStore, idGenerator, taskRegistry, workerId, maxAttempts,
                leaseMillis, heartbeatScheduler, retryCheckpointStore, stageCommandStore, stageExecutor,
                null, schedulingPolicy, executionProfileService, null);
    }

    /**
     * Creates a dispatcher with an explicit host finalization boundary.
     *
     * @param stageFinalizationPort transaction boundary for task/event/command/job updates
     */
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
            TaskRetryCheckpointStore retryCheckpointStore,
            RequirementStageCommandStore stageCommandStore,
            RequirementStageExecutor stageExecutor,
            RequirementStageFinalizationPort stageFinalizationPort,
            RequirementDeliverySchedulingPolicy schedulingPolicy,
            AgentExecutionProfileService executionProfileService
    ) {
        this(deliveryEngine, executor, jobStore, idGenerator, taskRegistry, workerId, maxAttempts,
                leaseMillis, heartbeatScheduler, retryCheckpointStore, stageCommandStore, stageExecutor,
                stageFinalizationPort, schedulingPolicy, executionProfileService, null);
    }

    /**
     * Creates a dispatcher with exhaustion provenance dependencies for checkpoint-bound retries.
     *
     * @param attemptBindingStore checkpoint-scoped attempt bindings used by exhaustion drafts
     */
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
            TaskRetryCheckpointStore retryCheckpointStore,
            RequirementStageCommandStore stageCommandStore,
            RequirementStageExecutor stageExecutor,
            RequirementStageFinalizationPort stageFinalizationPort,
            RequirementDeliverySchedulingPolicy schedulingPolicy,
            AgentExecutionProfileService executionProfileService,
            TaskRetryAttemptBindingStore attemptBindingStore
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
        this.attemptBindingStore = attemptBindingStore;
        this.exhaustionProvenanceFactory = retryCheckpointStore != null && attemptBindingStore != null
                ? new TaskRetryExhaustionProvenanceFactory(retryCheckpointStore, attemptBindingStore)
                : null;
        this.stageCommandStore = stageCommandStore == null
                ? new InMemoryRequirementStageCommandStore() : stageCommandStore;
        RequirementStageExecutor configuredExecutor = stageExecutor == null
                ? new EngineRequirementStageExecutor(deliveryEngine)
                : stageExecutor;
        this.stageExecutor = bridgeLegacyExecuteOnlyExecutor(configuredExecutor);
        this.stageFinalizationPort = stageFinalizationPort == null
                ? new InMemoryRequirementStageFinalizationPort(this.stageCommandStore, this.jobStore)
                : stageFinalizationPort;
        this.schedulingPolicy = schedulingPolicy == null
                ? RequirementDeliverySchedulingPolicy.defaults() : schedulingPolicy;
        this.executionProfileService = executionProfileService;
        this.stageCommandFactory = new RequirementStageCommandFactory(
                this.idGenerator, this.maxAttempts, this.schedulingPolicy, this.executionProfileService);
    }

    /** Wires the durable approval-resume consumer when the policy transaction adapter is present. */
    @Autowired(required = false)
    void setRequirementPolicyTransactionPort(RequirementPolicyTransactionPort requirementPolicyTransactionPort) {
        this.requirementPolicyTransactionPort = requirementPolicyTransactionPort;
    }

    /** Wires the authoritative policy generation store when the policy control plane is present. */
    @Autowired(required = false)
    void setRequirementPolicyRunStore(RequirementPolicyRunStore requirementPolicyRunStore) {
        this.requirementPolicyRunStore = requirementPolicyRunStore;
    }

    /** Wires the publication ledger when publication exhaustion provenance must be resolved. */
    @Autowired(required = false)
    void setRequirementPublicationStore(RequirementPublicationStore requirementPublicationStore) {
        this.requirementPublicationStore = requirementPublicationStore;
    }

    /**
     * Enqueues one requirement delivery run.
     *
     * @param taskId RD requirement task ID
     * @return future completed with the delivery result or the execution failure
     */
    public synchronized CompletableFuture<RequirementDeliveryResult> submit(String taskId) {
        long now = System.currentTimeMillis();
        // The stage command captures the authoritative task concurrency snapshot. Do not create
        // a recoverable umbrella job until that snapshot has passed the fenced preflight.
        RequirementStageCommand stageCommand = enqueueStageCommand(taskId, now);
        // Dedicated policy controls own their command retry and transaction state. A parallel
        // umbrella row would later try to reconstruct a generic stage from WAITING_POLICY or a
        // terminal policy status, reopening the legacy authorization path.
        if (!isPolicyEvaluateCommand(stageCommand) && !isPolicyApplyCommand(stageCommand)) {
            jobStore.enqueue(RequirementDeliveryJob.pending(
                    idGenerator.nextIdString(), taskId, maxAttempts, now));
        }
        observe(() -> metrics.recordEnqueued(stageCommand, now));
        CompletableFuture<RequirementDeliveryResult> future = pendingFutures.computeIfAbsent(
                stageCommand.commandId(), ignored -> new CompletableFuture<>());
        claimAndSchedule(now, stageCommand.commandId(), future);
        return future;
    }

    /** Schedules one durable command without reconstructing a task-wide generic producer. */
    public synchronized CompletableFuture<RequirementDeliveryResult> schedulePersistedCommand(String commandId) {
        String normalizedCommandId = commandId == null ? "" : commandId.strip();
        if (normalizedCommandId.isBlank()) {
            throw new IllegalArgumentException("persisted stage command id must not be blank");
        }
        RequirementStageCommand command = stageCommandStore.findById(normalizedCommandId)
                .orElseThrow(() -> new IllegalStateException("persisted stage command not found: " + normalizedCommandId));
        CompletableFuture<RequirementDeliveryResult> future = pendingFutures.computeIfAbsent(
                command.commandId(), ignored -> new CompletableFuture<>());
        claimAndSchedule(System.currentTimeMillis(), command.commandId(), future);
        return future;
    }

    /** Requeues pending, retryable, and expired-lease jobs after process or pool interruption. */
    @Scheduled(fixedDelayString = "${rd.requirement-delivery.recovery-interval-millis:30000}")
    public synchronized void recover() {
        long now = System.currentTimeMillis();
        for (RequirementStageCommand expired : stageCommandStore.deadLetterExpired(
                now, Math.max(schedulingPolicy.limits().batchSize() * 4, 64))) {
            if (!isPolicyOwnedControlCommand(expired)) {
                try {
                    exhaustTechnically(
                            expired,
                            expired.lastError(),
                            RdTaskStatus.DEAD_LETTERED,
                            TaskRetryCheckpointStatus.FAILED_NEEDS_HUMAN,
                            "TECHNICAL_EXHAUSTED");
                } catch (RuntimeException exhaustionFailure) {
                    log.warn("expired stage command exhaustion failed; lease/command remain recoverable, "
                                    + "commandId={}, reason={}",
                            expired.commandId(), safeError(exhaustionFailure));
                }
            }
        }
        // Migrate pre-stage-command umbrella rows into their durable stage identity once. The
        // fair claim below still operates exclusively on stage commands; the legacy rows are not
        // selected or leased by the recovery planner.
        int recoveryLimit = Math.max(schedulingPolicy.limits().batchSize() * 4, 64);
        for (RequirementDeliveryJob job : jobStore.recoverable(now, recoveryLimit)) {
            if (job.isExpiredRunning(now) && job.isAttemptBudgetExhausted()) {
                jobStore.deadLetterExpiredExhausted(job.taskId(), now, "lease expired at max attempts")
                        .ifPresent(dead -> markTaskDeadLettered(dead.taskId(), dead.errorMessage()));
                continue;
            }
            enqueueStageCommand(job.taskId(), now);
        }
        reconcilePendingFutures();
        claimAndSchedule(now, null, null);
    }

    /**
     * A future is process-local, while a stage command is shared by all dispatchers. If another
     * dispatcher reaches a terminal command state, this instance cannot recover the workflow
     * result (a stage may have a continuation), so it must release the caller explicitly instead
     * of waiting forever for a claim that can no longer happen.
     */
    private void reconcilePendingFutures() {
        for (Map.Entry<String, CompletableFuture<RequirementDeliveryResult>> entry
                : pendingFutures.entrySet()) {
            String commandId = entry.getKey();
            RequirementStageCommand command;
            try {
                command = stageCommandStore.findById(commandId).orElse(null);
            } catch (RuntimeException exception) {
                log.warn("requirement stage command lookup failed while reconciling local future, "
                                + "commandId={}, reason={}", commandId, safeError(exception));
                continue;
            }
            if (command == null) {
                continue;
            }
            switch (command.status()) {
                case SUCCEEDED -> failPendingFuture(commandId,
                        "requirement delivery stage completed by another worker: " + commandId);
                case DEAD_LETTERED -> failPendingFuture(commandId,
                        "requirement delivery stage dead-lettered by another worker: " + commandId
                                + (command.lastError().isBlank() ? "" : ": " + command.lastError()));
                case CANCELLED -> failPendingFuture(commandId,
                        "requirement delivery stage cancelled by another worker: " + commandId);
                default -> {
                    // PENDING, RUNNING, and FAILED_RETRYABLE remain eligible for this process.
                }
            }
        }
    }

    /**
     * Applies project/resource fairness before submitting a local runnable. The command remains
     * PENDING until the runnable obtains its atomic lease, so a saturated executor never loses a
     * durable stage command.
     */
    private synchronized void scheduleStageCommand(
            RequirementStageCommand stageCommand,
            CompletableFuture<RequirementDeliveryResult> future,
            long nowEpochMillis
    ) {
        if (stageCommand != null && future != null) {
            pendingFutures.putIfAbsent(stageCommand.commandId(), future);
        }
        claimAndSchedule(nowEpochMillis, stageCommand.commandId(), future);
    }

    private void scheduleClaimedStageCommand(
            RequirementStageCommand stageCommand,
            CompletableFuture<RequirementDeliveryResult> future
    ) {
        try {
            executor.execute(() -> runClaimedCommand(stageCommand, future));
        } catch (TaskRejectedException exception) {
            observe(metrics::recordQueueRejected);
            requeueRejectedStageCommand(stageCommand, exception);
            future.completeExceptionally(exception);
            log.warn("requirement delivery queue rejected a leased stage command; command was requeued, taskId={}",
                    stageCommand.taskId());
        }
    }

    private void requeueRejectedStageCommand(
            RequirementStageCommand stageCommand,
            RuntimeException rejection
    ) {
        try {
            if (isTechnicalAttemptExhausted(stageCommand) && !isPolicyOwnedControlCommand(stageCommand)) {
                exhaustTechnically(
                        stageCommand,
                        safeError(rejection),
                        RdTaskStatus.DEAD_LETTERED,
                        TaskRetryCheckpointStatus.FAILED_NEEDS_HUMAN,
                        "TECHNICAL_EXHAUSTED");
                return;
            }
            RequirementStageCommand failed = stageCommandStore.fail(
                    stageCommand, workerId, safeError(rejection), ownedNow(stageCommand));
            if (failed.status() == RequirementStageCommand.Status.DEAD_LETTERED
                    && !isPolicyOwnedControlCommand(failed)) {
                exhaustTechnically(
                        failed,
                        failed.lastError(),
                        RdTaskStatus.DEAD_LETTERED,
                        TaskRetryCheckpointStatus.FAILED_NEEDS_HUMAN,
                        "TECHNICAL_EXHAUSTED");
            }
        } catch (RuntimeException releaseFailure) {
            log.warn("failed to requeue rejected stage command; lease remains recoverable, commandId={}, reason={}",
                    stageCommand.commandId(), safeError(releaseFailure));
        }
    }

    /**
     * Runs the same fair planner and bounded selected claim for submit and recovery. A requested
     * command receives the caller future when it is selected; other selected commands are
     * recovery continuations with independent completion futures.
     */
    private void claimAndSchedule(
            long nowEpochMillis,
            String requestedCommandId,
            CompletableFuture<RequirementDeliveryResult> requestedFuture
    ) {
        List<RequirementStageCommand> candidates = stageCommandStore.recoverable(
                nowEpochMillis, Math.max(schedulingPolicy.limits().batchSize() * 4, 64), schedulingPolicy);
        List<RequirementStageCommand> inFlight = schedulingInFlight(
                nowEpochMillis, Math.max(schedulingPolicy.limits().batchSize() * 4, 64));
        Map<ScheduleResourceClass, Integer> inFlightByResource = new HashMap<>();
        for (RequirementStageCommand command : inFlight) {
            for (ScheduleResourceClass resource : FairRequirementDeliveryClaimPlanner.resourceRequirementsFor(command)) {
                inFlightByResource.merge(resource, 1, Integer::sum);
            }
        }
        observe(() -> metrics.recordInFlight(inFlightByResource, schedulingPolicy.limits()));
        List<RequirementStageCommand> planned = claimPlanner.planStageCommands(
                candidates, inFlight, schedulingPolicy.limits(), nowEpochMillis, schedulingPolicy.projectWeights());
        List<RequirementStageCommand> claimed = stageCommandStore.claimFairBatch(
                planned.stream().map(RequirementStageCommand::commandId).toList(),
                workerId,
                nowEpochMillis,
                leaseMillis,
                schedulingPolicy);
        for (RequirementStageCommand claimedCommand : claimed) {
            observe(() -> metrics.recordClaimed(claimedCommand, nowEpochMillis));
            // Remove the association only after the durable claim succeeds. If the planner
            // deferred this command, the map entry must survive until a later recovery tick.
            CompletableFuture<RequirementDeliveryResult> future = pendingFutures.remove(
                    claimedCommand.commandId());
            if (future == null && claimedCommand.commandId().equals(requestedCommandId)
                    && requestedFuture != null) {
                future = requestedFuture;
            }
            if (future == null) {
                future = new CompletableFuture<>();
            }
            scheduleClaimedStageCommand(claimedCommand, future);
        }
    }

    /**
     * Includes legacy umbrella leases in the fairness snapshot while old rows are being
     * migrated to stage commands. A recovered process must not admit another command for a
     * project merely because the old job has not yet acquired a stage identity.
     */
    private List<RequirementStageCommand> schedulingInFlight(long nowEpochMillis, int limit) {
        int boundedLimit = Math.max(1, limit);
        List<RequirementStageCommand> active = new ArrayList<>(
                stageCommandStore.inFlight(nowEpochMillis, boundedLimit));
        Set<String> stageTaskIds = new HashSet<>();
        for (RequirementStageCommand command : active) {
            if (command != null) {
                stageTaskIds.add(command.taskId());
            }
        }
        List<RequirementDeliveryJob> legacy = jobStore.listInFlight(nowEpochMillis);
        for (RequirementDeliveryJob job : legacy) {
            if (job == null || stageTaskIds.contains(job.taskId()) || active.size() >= boundedLimit) {
                continue;
            }
            String projectId = projectIdForTask(job.taskId());
            active.add(new RequirementStageCommand(
                    "legacy-" + job.jobId(), job.taskId(), 0L, 0L,
                    "REQUIREMENT_DELIVERY", "LEGACY_WORKFLOW", job.attemptNo(),
                    Math.max(1, job.maxAttempts()), 0L, ScheduleResourceClass.GENERIC,
                    projectId, "", 2, RequirementStageCommand.Status.RUNNING,
                    job.leaseOwner(), job.leaseUntilEpochMillis(), nowEpochMillis, "",
                    job.createTimeEpochMillis(), job.updateTimeEpochMillis()));
            stageTaskIds.add(job.taskId());
        }
        return List.copyOf(active);
    }

    private String projectIdForTask(String taskId) {
        if (taskRegistry == null || taskId == null || taskId.isBlank()) {
            return "_default";
        }
        try {
            RdRequirementTask task = taskRegistry.getRequirementTask(taskId);
            if (task.projectId() == null || task.projectId().isBlank()) {
                return "_default";
            }
            return task.projectId();
        } catch (RuntimeException exception) {
            return "_default";
        }
    }

    private void runClaimedCommand(
            RequirementStageCommand stageCommand,
            CompletableFuture<RequirementDeliveryResult> future
    ) {
        if (isPolicyEvaluateCommand(stageCommand)) {
            runPolicyEvaluateCommand(stageCommand, future);
            return;
        }
        if (isPolicyApplyCommand(stageCommand)) {
            runPolicyApplyCommand(stageCommand, future);
            return;
        }
        if (isApprovalResumeCommand(stageCommand)) {
            runApprovalResumeCommand(stageCommand, future);
            return;
        }
        if (isLegacyPolicyCommand(stageCommand)) {
            IllegalStateException failure = new IllegalStateException(
                    "legacy POLICY stage is quarantined; durable POLICY_EVALUATE is required");
            failPolicyControlCommand(stageCommand, safeError(failure));
            future.completeExceptionally(failure);
            return;
        }
        String taskId = stageCommand.taskId();
        // The umbrella delivery job is retained for existing status/audit consumers, but it is
        // deliberately advisory. A recovered continuation must never be blocked by its absence
        // or terminal state; the stage command lease is the sole execution authority.
        RequirementDeliveryJob job = claimUmbrellaJob(taskId);
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeat = null;
        RequirementStageFinalization marker = null;
        try {
            heartbeat = scheduleStageHeartbeat(stageCommand, leaseLost);
            if (leaseLost.get()) {
                throw new IllegalStateException("requirement delivery lease lost before execution: " + taskId);
            }
            RequirementStageFinalization recoveredMarker = stageFinalizationPort
                    .findLatestPrepared(stageCommand.commandId()).orElse(null);
            marker = recoveredMarker == null
                    ? stageFinalizationPort.prepare(stageCommand, workerId, expectedTaskStatus(stageCommand),
                            System.currentTimeMillis())
                    : recoveredMarker;
            if (!marker.matchesCommandIdentity(stageCommand)) {
                throw new IllegalStateException("recovered finalization identity conflicts: " + stageCommand.commandId());
            }
            RequirementStageExecutionPlan plan;
            RequirementStageFinalizationPort.TaskMutationDisposition mutationDisposition;
            if (marker.isOutcomeRecorded()) {
                plan = stageFinalizationPort.decodeOutcomePlan(marker);
                mutationDisposition = recordedOutcomeMutationDisposition(stageCommand, plan);
            } else {
                expectedTaskStatus(stageCommand);
                plan = stageExecutor.plan(stageCommand);
                if (plan == null) {
                    throw new IllegalStateException("stage executor returned no non-mutating plan: "
                            + stageCommand.commandId());
                }
                marker = stageFinalizationPort.recordOutcome(
                        marker, stageCommand, workerId, plan, System.currentTimeMillis());
                plan = stageFinalizationPort.decodeOutcomePlan(marker);
                mutationDisposition = RequirementStageFinalizationPort.TaskMutationDisposition.APPLY;
            }
            RequirementDeliveryResult result = resultForPlan(plan);
            RequirementDeliveryResult bridged = bridgedLegacyResults.get();
            if (bridged != null && plan.mutations().isEmpty()) {
                result = bridged;
            }
            bridgedLegacyResults.remove();
            cancelHeartbeat(heartbeat);
            if (leaseLost.get()) {
                throw new IllegalStateException("requirement delivery lease lost during execution: " + taskId);
            }
            boolean continued = finalizeStageOutcome(stageCommand, job, marker, plan, result,
                    mutationDisposition, future);
            if (!continued) {
                completeRetryCheckpoint(taskId, checkpointStatus(plan, result), result.errorMessage());
                future.complete(result);
            }
        } catch (RuntimeException | LinkageError exception) {
            bridgedLegacyResults.remove();
            cancelHeartbeat(heartbeat);
            if (leaseLost.get()) {
                observe(metrics::recordLeaseLost);
            }
            boolean recordedOutcomeMustRemainRecoverable = marker != null && marker.isOutcomeRecorded();
            if (!recordedOutcomeMustRemainRecoverable) {
                if (isTechnicalAttemptExhausted(stageCommand)) {
                    exhaustTechnically(
                            stageCommand,
                            safeError(exception),
                            RdTaskStatus.FAILED_RETRYABLE,
                            TaskRetryCheckpointStatus.FAILED_RETRYABLE,
                            "TECHNICAL_EXHAUSTED");
                } else {
                    // Non-final technical failures keep the task snapshot runnable so the same
                    // command can be reclaimed. LinkageError is not lease-recoverable: mark the
                    // task FAILED_RETRYABLE with a stage-derived phase so the job is not stranded.
                    failStageCommand(stageCommand, safeError(exception));
                    if (exception instanceof LinkageError) {
                        markTaskRetryableAfterException(stageCommand, exception);
                    }
                }
                try {
                    if (!leaseLost.get() && job != null) {
                        jobStore.fail(job.jobId(), workerId, safeError(exception), ownedNow(stageCommand));
                    }
                } catch (RuntimeException leaseException) {
                    log.warn("requirement delivery fail after lease loss, taskId={}, reason={}",
                            taskId, safeError(leaseException));
                }
            }
            log.error("requirement delivery failed in isolated thread pool, taskId={}", taskId, exception);
            future.completeExceptionally(exception);
        }
    }

    /** Runs the read-only policy gate, then records its durable decision transactionally. */
    private void runPolicyEvaluateCommand(
            RequirementStageCommand stageCommand,
            CompletableFuture<RequirementDeliveryResult> future
    ) {
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeat = null;
        try {
            requireRunningPolicyControlIdentity(stageCommand, "POLICY_EVALUATE");
            heartbeat = scheduleStageHeartbeat(stageCommand, leaseLost);
            if (leaseLost.get()) {
                throw new IllegalStateException("policy-evaluate lease lost before evaluation: "
                        + stageCommand.commandId());
            }
            RequirementPolicyRunStore policyStore = requirementPolicyRunStore;
            RequirementPolicyTransactionPort policyPort = requirementPolicyTransactionPort;
            if (policyStore == null || policyPort == null) {
                throw new IllegalStateException("policy-evaluate control plane is not configured");
            }

            RequirementPolicyRun existing = policyStore.findById(stageCommand.policyRunId()).orElse(null);
            if (existing == null) {
                throw new IllegalStateException("policy-evaluate command has no prepared durable generation: "
                        + stageCommand.commandId());
            }
            RecordRequirementPolicyDecisionCommand decision;
            if (existing != null && existing.state() == RequirementPolicyRunState.POLICY_DECIDED) {
                requirePolicyGenerationIdentity(existing, stageCommand);
                decision = decisionFrom(existing);
            } else {
                RequirementPolicyEvaluationProposal proposal = deliveryEngine.evaluateFrozenPolicy(stageCommand);
                requirePolicyProposalIdentity(proposal, stageCommand);
                requirePlanReadyIdentity(existing, proposal, stageCommand);
                decision = proposal.toRecordCommand(stageCommand.policyRunId());
            }

            // The gate and generation create/replay happen before this transaction boundary.
            cancelHeartbeat(heartbeat);
            heartbeat = null;
            if (leaseLost.get()) {
                throw new IllegalStateException("policy-evaluate lease lost before decision commit: "
                        + stageCommand.commandId());
            }
            RequirementPolicyEvaluationResult recorded = policyPort.recordEvaluation(
                    decision, stageCommand, workerId, System.currentTimeMillis());
            if (recorded == null
                    || !stageCommand.commandId().equals(recorded.completedEvaluationCommand().commandId())
                    || !stageCommand.policyRunId().equals(recorded.policyRun().id())
                    || !stageCommand.policyRunId().equals(recorded.completedEvaluationCommand().policyRunId())
                    || !stageCommand.commandId().equals(recorded.policyRun().id())
                    || !stageCommand.taskId().equals(recorded.policyApplyCommand().taskId())
                    || !stageCommand.policyRunId().equals(recorded.policyApplyCommand().policyRunId())) {
                throw new IllegalStateException("policy-evaluate consumer returned mismatched durable result: "
                        + stageCommand.commandId());
            }
            long now = System.currentTimeMillis();
            observe(() -> metrics.recordCompleted(stageCommand, now));
            observe(() -> metrics.recordEnqueued(recorded.policyApplyCommand(), now));
            scheduleStageCommand(recorded.policyApplyCommand(), future, now);
        } catch (RuntimeException | LinkageError exception) {
            cancelHeartbeat(heartbeat);
            if (leaseLost.get()) {
                observe(metrics::recordLeaseLost);
            }
            failPolicyControlCommand(stageCommand, safeError(exception));
            log.error("policy-evaluate control command failed, commandId={}, taskId={}",
                    stageCommand.commandId(), stageCommand.taskId(), exception);
            future.completeExceptionally(exception);
        }
    }

    /** Consumes the already-decided policy without invoking the generic stage executor. */
    private void runPolicyApplyCommand(
            RequirementStageCommand stageCommand,
            CompletableFuture<RequirementDeliveryResult> future
    ) {
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeat = null;
        try {
            requireRunningPolicyControlIdentity(stageCommand, "POLICY_APPLY");
            heartbeat = scheduleStageHeartbeat(stageCommand, leaseLost);
            if (leaseLost.get()) {
                throw new IllegalStateException("policy-apply lease lost before consumption: "
                        + stageCommand.commandId());
            }
            RequirementPolicyTransactionPort policyPort = requirementPolicyTransactionPort;
            if (policyPort == null) {
                throw new IllegalStateException("policy-apply transaction port is not configured");
            }
            cancelHeartbeat(heartbeat);
            heartbeat = null;
            if (leaseLost.get()) {
                throw new IllegalStateException("policy-apply lease lost before consumption: "
                        + stageCommand.commandId());
            }
            RequirementPolicyApplyResult applied = policyPort.consumePolicyApply(
                    stageCommand, workerId, System.currentTimeMillis());
            if (applied == null
                    || !stageCommand.commandId().equals(applied.completedApplyCommand().commandId())
                    || !stageCommand.taskId().equals(applied.policyRun().taskId())
                    || !stageCommand.policyRunId().equals(applied.policyRun().id())
                    || !stageCommand.policyRunId().equals(applied.completedApplyCommand().policyRunId())) {
                throw new IllegalStateException("policy-apply consumer returned mismatched durable result: "
                        + stageCommand.commandId());
            }
            long now = System.currentTimeMillis();
            observe(() -> metrics.recordCompleted(stageCommand, now));
            if (applied.disposition() == RequirementPolicyApplyDisposition.ALLOWED) {
                observe(() -> metrics.recordEnqueued(applied.nextCommand(), now));
                scheduleStageCommand(applied.nextCommand(), future, now);
                return;
            }
            RdTaskStatus status = applied.disposition() == RequirementPolicyApplyDisposition.WAITING_APPROVAL
                    ? RdTaskStatus.WAITING_APPROVAL : RdTaskStatus.FAILED_NEEDS_HUMAN;
            String error = applied.disposition() == RequirementPolicyApplyDisposition.DENIED
                    ? policyReason(applied.policyRun().policyJson(), applied.policyRun().policyAction()) : "";
            future.complete(new RequirementDeliveryResult(
                    applied.policyRun().taskId(), status, "", applied.policyRun().policyJson(), error));
        } catch (RuntimeException | LinkageError exception) {
            cancelHeartbeat(heartbeat);
            if (leaseLost.get()) {
                observe(metrics::recordLeaseLost);
            }
            failPolicyControlCommand(stageCommand, safeError(exception));
            log.error("policy-apply control command failed, commandId={}, taskId={}",
                    stageCommand.commandId(), stageCommand.taskId(), exception);
            future.completeExceptionally(exception);
        }
    }

    private void requireRunningPolicyControlIdentity(RequirementStageCommand command, String stage) {
        if (command == null
                || !"REQUIREMENT_DELIVERY".equals(command.role())
                || !stage.equals(command.stage())
                || command.status() != RequirementStageCommand.Status.RUNNING
                || !workerId.equals(command.leaseOwner())
                || command.taskVersion() < 0L
                || command.fencingToken() <= 0L
                || command.policyRunId().isBlank()
                || ("POLICY_EVALUATE".equals(stage)
                && !command.commandId().equals(command.policyRunId()))) {
            throw new IllegalStateException("policy control command identity is invalid: "
                    + (command == null ? "null" : command.commandId()));
        }
    }

    private static RequirementPolicyRun planReadyLedger(
            RequirementStageCommand command, RequirementPolicyEvaluationProposal proposal, long now
    ) {
        return new RequirementPolicyRun(
                command.commandId(), command.taskId(), command.taskVersion(), command.fencingToken(),
                proposal.planJson(), proposal.planDigest(), "", "", "",
                RequirementPolicyRunState.PLAN_READY, command.taskVersion(), command.fencingToken(),
                null, null, "", "", "", 0L, "", "", 0L, 0L, now, now);
    }

    private static void requirePolicyProposalIdentity(
            RequirementPolicyEvaluationProposal proposal, RequirementStageCommand command
    ) {
        if (proposal == null
                || !command.taskId().equals(proposal.taskId())
                || command.taskVersion() != proposal.taskVersion()
                || command.fencingToken() != proposal.fencingToken()) {
            throw new IllegalStateException("policy proposal conflicts with evaluate command: "
                    + command.commandId());
        }
    }

    private static void requirePolicyGenerationIdentity(
            RequirementPolicyRun run, RequirementStageCommand command
    ) {
        if (!command.commandId().equals(run.id())
                || !command.taskId().equals(run.taskId())
                || command.taskVersion() != run.sourceTaskVersion()
                || command.fencingToken() != run.sourceFencingToken()) {
            throw new IllegalStateException("policy generation conflicts with evaluate command: "
                    + command.commandId());
        }
    }

    private static void requirePlanReadyIdentity(
            RequirementPolicyRun run, RequirementPolicyEvaluationProposal proposal,
            RequirementStageCommand command
    ) {
        requirePolicyGenerationIdentity(run, command);
        if (run.state() != RequirementPolicyRunState.PLAN_READY
                || run.boundTaskVersion() != command.taskVersion()
                || run.boundFencingToken() != command.fencingToken()
                || !run.planDigest().equals(proposal.planDigest())
                || !RequirementPolicyRun.canonicalizeJson(run.planJson()).equals(proposal.planJson())) {
            throw new IllegalStateException("PLAN_READY ledger conflicts with frozen policy proposal: "
                    + command.commandId());
        }
    }

    private static RecordRequirementPolicyDecisionCommand decisionFrom(RequirementPolicyRun run) {
        return new RecordRequirementPolicyDecisionCommand(
                run.id(), run.taskId(), run.sourceTaskVersion(), run.sourceFencingToken(), run.planDigest(),
                RequirementPolicyRun.canonicalizeJson(run.policyJson()), run.policyDigest(), run.policyAction());
    }

    private static String policyReason(String policyJson, String fallback) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = OBJECT_MAPPER.readTree(policyJson);
            com.fasterxml.jackson.databind.JsonNode reason = root == null ? null : root.get("reason");
            return reason != null && reason.isTextual() && !reason.textValue().isBlank()
                    ? reason.textValue().strip() : fallback;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return fallback;
        }
    }

    /**
     * Consumes the policy-owned control command without touching the generic executor,
     * finalizer, task retry state, or advisory umbrella job. The transaction port owns the
     * task CAS, ledger transition, current-command completion, and durable first-role insert.
     */
    private void runApprovalResumeCommand(
            RequirementStageCommand stageCommand,
            CompletableFuture<RequirementDeliveryResult> future
    ) {
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeat = null;
        try {
            requireRunningPolicyControlIdentity(stageCommand, "APPROVAL_RESUME");
            heartbeat = scheduleStageHeartbeat(stageCommand, leaseLost);
            if (leaseLost.get()) {
                throw new IllegalStateException("approval-resume lease lost before consumption: "
                        + stageCommand.commandId());
            }
            RequirementPolicyTransactionPort policyPort = requirementPolicyTransactionPort;
            if (policyPort == null) {
                throw new IllegalStateException("approval-resume policy transaction port is not configured");
            }
            // Stop renewing before the control transaction takes ownership. A heartbeat already
            // in flight can observe the command as SUCCEEDED immediately after that transaction
            // commits; its terminal-store error must not turn an authoritative resume result
            // into a false retry. A lease loss observed before this boundary still fails closed.
            cancelHeartbeat(heartbeat);
            heartbeat = null;
            if (leaseLost.get()) {
                throw new IllegalStateException("approval-resume lease lost before consumption: "
                        + stageCommand.commandId());
            }
            RequirementPolicyResumeResult resumed = policyPort.consumeApproval(
                    stageCommand, workerId, System.currentTimeMillis());
            if (resumed == null
                    || !stageCommand.commandId().equals(resumed.completedResumeCommand().commandId())
                    || !stageCommand.taskId().equals(resumed.nextCommand().taskId())
                    || !stageCommand.policyRunId().equals(resumed.policyRun().id())
                    || !stageCommand.policyRunId().equals(resumed.completedResumeCommand().policyRunId())) {
                throw new IllegalStateException("approval-resume consumer returned mismatched durable result: "
                        + stageCommand.commandId());
            }
            long now = System.currentTimeMillis();
            observe(() -> metrics.recordCompleted(stageCommand, now));
            observe(() -> metrics.recordEnqueued(resumed.nextCommand(), now));
            // The consumer already inserted this continuation in its transaction. Scheduling it
            // must claim the exact durable identity, never enqueue a generic policy/role command.
            scheduleStageCommand(resumed.nextCommand(), future, now);
        } catch (RuntimeException | LinkageError exception) {
            cancelHeartbeat(heartbeat);
            if (leaseLost.get()) {
                observe(metrics::recordLeaseLost);
            }
            failApprovalResumeCommand(stageCommand, safeError(exception));
            log.error("approval-resume control command failed, commandId={}, taskId={}",
                    stageCommand.commandId(), stageCommand.taskId(), exception);
            future.completeExceptionally(exception);
        }
    }

    private boolean isApprovalResumeCommand(RequirementStageCommand stageCommand) {
        return stageCommand != null
                && "APPROVAL_RESUME".equals(stageCommand.stage());
    }

    private boolean isPolicyEvaluateCommand(RequirementStageCommand stageCommand) {
        return stageCommand != null && "POLICY_EVALUATE".equals(stageCommand.stage());
    }

    private boolean isPolicyApplyCommand(RequirementStageCommand stageCommand) {
        return stageCommand != null && "POLICY_APPLY".equals(stageCommand.stage());
    }

    private boolean isLegacyPolicyCommand(RequirementStageCommand stageCommand) {
        return stageCommand != null && "POLICY".equals(stageCommand.stage());
    }

    private boolean isPolicyOwnedControlCommand(RequirementStageCommand stageCommand) {
        return isPolicyEvaluateCommand(stageCommand)
                || isPolicyApplyCommand(stageCommand)
                || isApprovalResumeCommand(stageCommand)
                || isLegacyPolicyCommand(stageCommand);
    }

    /**
     * Commits every durable stage outcome before making its continuation visible to the scheduler.
     *
     * <p>The finalizer owns task CAS/event append, current-command completion, continuation insert,
     * advisory job state and marker closure as one host transaction. No caller may compensate a
     * failed finalizer by directly enqueueing the continuation.
     */
    private boolean finalizeStageOutcome(
            RequirementStageCommand stageCommand,
            RequirementDeliveryJob job,
            RequirementStageFinalization marker,
            RequirementStageExecutionPlan plan,
            RequirementDeliveryResult result,
            RequirementStageFinalizationPort.TaskMutationDisposition mutationDisposition,
            CompletableFuture<RequirementDeliveryResult> future
    ) {
        RequirementStageCommand proposedNext = continuationCommand(
                stageCommand, plan, System.currentTimeMillis());
        RequirementStageFinalizationPort.FinalizationResult finalized = stageFinalizationPort.finalize(
                new RequirementStageFinalizationPort.FinalizationCommand(
                        marker,
                        stageCommand,
                        workerId,
                        plan,
                        proposedNext,
                        job,
                        jobDisposition(plan.commandDisposition()),
                        mutationDisposition,
                        System.currentTimeMillis()));
        RequirementStageCommand next = finalized.nextCommand();
        CommandDisposition disposition = plan.commandDisposition();
        if (disposition == CommandDisposition.SUCCEEDED) {
            observe(() -> metrics.recordCompleted(stageCommand, System.currentTimeMillis()));
        } else if (disposition == CommandDisposition.RETRYABLE_TECHNICAL_FAILURE) {
            observe(metrics::recordRetry);
        }
        if (next == null || next.commandId().equals(stageCommand.commandId())) {
            return false;
        }
        observe(() -> metrics.recordEnqueued(next, System.currentTimeMillis()));
        scheduleStageCommand(next, future, System.currentTimeMillis());
        return true;
    }

    private RequirementStageCommand continuationCommand(
            RequirementStageCommand previous,
            RequirementStageExecutionPlan plan,
            long nowEpochMillis
    ) {
        if (plan.commandDisposition()
                != com.wish.rd.engine.requirement.job.model.CommandDisposition.SUCCEEDED
                || plan.continuation().isTerminal()) {
            return null;
        }
        String role = plan.continuation().role();
        String stage = plan.continuation().stage();
        if (!previous.remediationRoundId().isBlank()) {
            return stageCommandFactory.createRemediationPendingCommand(
                    "", previous.taskId(), plan.postVersion(), plan.postFencingToken(), role, stage,
                    previous.projectId(), priorityName(previous.priorityRank()), previous.providerId(),
                    previous.policyRunId(), previous.remediationRoundId(), previous.remediationKind(),
                    previous.remediationNo(), previous.remediationSourceStageRunId(),
                    previous.remediationRequestJson(), previous.remediationRequestHash(), nowEpochMillis);
        }
        if (previous.retryCheckpointId().isBlank()) {
            return stageCommandFactory.createPendingCommand(
                    previous.taskId(),
                    plan.postVersion(),
                    plan.postFencingToken(),
                    role,
                    stage,
                    previous.projectId(),
                    priorityName(previous.priorityRank()),
                    previous.providerId(),
                    previous.policyRunId(),
                    nowEpochMillis);
        }
        String targetBindingId = continuationTargetBindingId(previous.retryCheckpointId(), role, stage);
        if (targetBindingId.isBlank() && "AI_REVIEW".equals(stage)) {
            // AI_REVIEW 是控制面阶段：路由未包含 AI 复审重试时没有预绑定，
            // 降级为普通 pending 命令放行（retry-pending 工厂强制要求绑定 id）。
            return stageCommandFactory.createPendingCommand(
                    previous.taskId(),
                    plan.postVersion(),
                    plan.postFencingToken(),
                    role,
                    stage,
                    previous.projectId(),
                    priorityName(previous.priorityRank()),
                    previous.providerId(),
                    previous.policyRunId(),
                    nowEpochMillis);
        }
        return stageCommandFactory.createRetryPendingCommand(
                previous.taskId(),
                plan.postVersion(),
                plan.postFencingToken(),
                role,
                stage,
                previous.projectId(),
                priorityName(previous.priorityRank()),
                previous.providerId(),
                previous.policyRunId(),
                previous.retryCheckpointId(),
                previous.businessGeneration(),
                targetBindingId,
                nowEpochMillis);
    }

    private String continuationTargetBindingId(String checkpointId, String role, String stage) {
        boolean attemptTarget = "AI_REVIEW".equals(stage) || (stage != null && stage.startsWith("ROLE_EXECUTION:"));
        if (!attemptTarget) {
            return "";
        }
        if (attemptBindingStore == null) {
            throw new IllegalStateException(
                    "checkpoint-bound role continuation requires attempt bindings: " + checkpointId);
        }
        if ("AI_REVIEW".equals(stage)) {
            // AI_REVIEW 续跑按 review-run 身份（kind=AI_REVIEW，role 为空）绑定；
            // 路由未包含 AI 复审重试时没有预绑定，返回空串直接放行，不得阻塞交付。
            // 旧实现把 REQUIREMENT_DELIVERY 喂给 AgentRole.valueOf 必然抛错——
            // 2026-08-21 杭州真机：QA 成功后的 AI_REVIEW 续跑使任务卡死在 EXECUTING。
            return attemptBindingStore.findPrimary(checkpointId, TaskRetryAttemptKind.AI_REVIEW, null)
                    .map(binding -> binding.bindingId())
                    .orElse("");
        }
        AgentRole nextRole;
        try {
            nextRole = AgentRole.valueOf(role == null ? "" : role.strip());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("checkpoint-bound continuation role is invalid: " + role, exception);
        }
        return attemptBindingStore.findPrimary(checkpointId, TaskRetryAttemptKind.AGENT_STAGE, nextRole)
                .map(binding -> binding.bindingId())
                .orElseThrow(() -> new IllegalStateException(
                        "checkpoint-bound continuation is missing a pre-bound attempt: "
                                + checkpointId + ":" + nextRole));
    }

    private RequirementStageFinalizationPort.JobDisposition jobDisposition(
            com.wish.rd.engine.requirement.job.model.CommandDisposition disposition
    ) {
        return switch (disposition) {
            case SUCCEEDED -> RequirementStageFinalizationPort.JobDisposition.COMPLETE;
            case RETRYABLE_TECHNICAL_FAILURE -> RequirementStageFinalizationPort.JobDisposition.FAIL_RETRYABLE;
            case TERMINAL_FAILURE -> RequirementStageFinalizationPort.JobDisposition.CANCEL;
        };
    }

    private static RequirementDeliveryResult resultForPlan(RequirementStageExecutionPlan plan) {
        RequirementTaskMutation last = plan.mutations().isEmpty() ? null : plan.mutations().getLast();
        return new RequirementDeliveryResult(
                plan.taskId(), plan.postStatus(),
                last == null ? "" : last.pullRequestUrl(),
                last == null ? "{}" : (last.executionResultJson().isBlank() ? "{}" : last.executionResultJson()),
                last == null ? "" : last.errorMessage());
    }

    private RdTaskStatus expectedTaskStatus(RequirementStageCommand stageCommand) {
        if (taskRegistry == null) {
            throw new IllegalStateException("stage command requires an authoritative task registry");
        }
        RdTask current = taskRegistry.getTask(stageCommand.taskId());
        if (current == null
                || current.version() != stageCommand.taskVersion()
                || current.fencingToken() <= 0L
                || stageCommand.fencingToken() <= 0L
                || current.fencingToken() != stageCommand.fencingToken()) {
            throw new IllegalStateException("stage command fencing mismatch: " + stageCommand.commandId());
        }
        return current.status();
    }

    private RequirementStageFinalizationPort.TaskMutationDisposition recordedOutcomeMutationDisposition(
            RequirementStageCommand stageCommand,
            RequirementStageExecutionPlan plan
    ) {
        if (taskRegistry == null) {
            throw new IllegalStateException("stage command requires an authoritative task registry");
        }
        RdTask current = taskRegistry.getTask(stageCommand.taskId());
        if (current == null || !current.taskId().equals(plan.taskId())) {
            throw new IllegalStateException("stage outcome task snapshot is missing: " + stageCommand.commandId());
        }
        if (matchesTaskSnapshot(current, plan.expectedVersion(), plan.expectedFencingToken(), plan.expectedStatus())) {
            return RequirementStageFinalizationPort.TaskMutationDisposition.APPLY;
        }
        if (matchesTaskSnapshot(current, plan.postVersion(), plan.postFencingToken(), plan.postStatus())) {
            return RequirementStageFinalizationPort.TaskMutationDisposition.ALREADY_APPLIED;
        }
        throw new IllegalStateException("stage outcome task snapshot is stale: " + stageCommand.commandId());
    }

    private static boolean matchesTaskSnapshot(
            RdTask task, long version, long fencingToken, RdTaskStatus status
    ) {
        return task.version() == version
                && task.fencingToken() == fencingToken
                && task.status() == status;
    }

    private RequirementDeliveryResult alreadyAppliedMutation(RequirementStageFinalization marker) {
        if (taskRegistry == null) {
            return null;
        }
        RdTask current = taskRegistry.getTask(marker.taskId());
        RdTaskStatus plannedStatus = deterministicAppliedStatus(marker.stage());
        if (!(current instanceof RdRequirementTask task)
                || !task.taskId().equals(marker.taskId())
                || marker.expectedFencingToken() <= 0L
                || task.fencingToken() <= 0L
                || task.version() != marker.expectedTaskVersion() + 1L
                || task.fencingToken() != marker.expectedFencingToken() + 1L
                || plannedStatus == null
                || task.status() != plannedStatus) {
            return null;
        }
        return new RequirementDeliveryResult(task.taskId(), task.status(), task.pullRequestUrl(),
                task.executionResultJson(), task.errorMessage());
    }

    private RdTaskStatus deterministicAppliedStatus(String stage) {
        return switch (stage == null ? "" : stage) {
            case "MATERIAL_COLLECTING" -> RdTaskStatus.MATERIAL_COLLECTING;
            case "MATERIAL_READY" -> RdTaskStatus.MATERIAL_READY;
            case "CONTEXT_BUILDING" -> RdTaskStatus.CONTEXT_BUILDING;
            case "CONTEXT_READY" -> RdTaskStatus.CONTEXT_READY;
            case "PLAN_GENERATING" -> RdTaskStatus.PLAN_GENERATING;
            case "PLAN_GENERATED" -> RdTaskStatus.PLAN_GENERATED;
            default -> null;
        };
    }

    private RequirementDeliveryJob claimUmbrellaJob(String taskId) {
        try {
            return jobStore.claim(taskId, workerId, System.currentTimeMillis(), leaseMillis).orElse(null);
        } catch (RuntimeException exception) {
            log.debug("umbrella delivery job unavailable for stage command, taskId={}, reason={}",
                    taskId, safeError(exception));
            return null;
        }
    }

    private RequirementStageCommand enqueueStageCommand(String taskId, long nowEpochMillis) {
        if (taskRegistry == null) {
            throw new IllegalStateException("stage command requires an authoritative task registry");
        }
        RdTask task = taskRegistry.getTask(taskId);
        if (task == null || task.fencingToken() <= 0L) {
            throw new IllegalStateException("stage command requires a positive task fencing token: " + taskId);
        }
        String priority = task.priority();
        long taskVersion = task.version();
        long fencingToken = task.fencingToken();
        String projectId = task instanceof RdRequirementTask requirementTask && !requirementTask.projectId().isBlank()
                ? requirementTask.projectId() : "_default";
        StageTarget target = nextStageFor(taskId, task.status());
        RequirementStageCommand existing = stageCommandStore.find(taskId, target.role(), target.stage())
                .orElse(null);
        if (existing != null) {
            requireExactTaskIdentity(existing, task, "existing stage command");
            if (isPolicyEvaluateCommand(existing)) {
                requirePreparedPolicyEvaluation(existing, task);
            } else if (isPolicyApplyCommand(existing)) {
                requireDurablePolicyApply(existing, task);
            }
            return existing;
        }
        if ("POLICY_EVALUATE".equals(target.stage())) {
            return preparePolicyEvaluationCommand(task, projectId, priority, nowEpochMillis);
        }
        if ("POLICY_APPLY".equals(target.stage())) {
            throw new IllegalStateException("WAITING_POLICY requires the exact durable policy-apply command: " + taskId);
        }
        if (target.stage().startsWith("ROLE_EXECUTION:")) {
            throw new IllegalStateException(
                    "role execution may only start from a durable policy transaction: " + taskId);
        }
        RequirementStageCommand requested = stageCommandFactory.createPendingCommand(
                taskId,
                taskVersion,
                fencingToken,
                target.role(),
                target.stage(),
                projectId,
                priority,
                "",
                nowEpochMillis
        );
        RequirementStageCommand effective = stageCommandStore.enqueue(requested);
        requireExactEnqueueIdentity(requested, effective, "enqueued stage command");
        requireExactTaskIdentity(effective, task, "enqueued stage command");
        return effective;
    }

    /** Prepares the ledger and its FK-bound evaluate command before either can be claimed. */
    private RequirementStageCommand preparePolicyEvaluationCommand(
            RdTask task, String projectId, String priority, long nowEpochMillis
    ) {
        if (deliveryEngine == null || requirementPolicyTransactionPort == null) {
            throw new IllegalStateException("policy-evaluate preparation is not configured");
        }
        RequirementStageCommand requested = stageCommandFactory.createPendingCommand(
                task.taskId(), task.version(), task.fencingToken(), "REQUIREMENT_DELIVERY", "POLICY_EVALUATE",
                projectId, priority, "", nowEpochMillis);
        if (!requested.commandId().equals(requested.policyRunId())) {
            throw new IllegalStateException("policy-evaluate command must use its command id as policy run id: "
                    + requested.commandId());
        }
        RequirementPolicyEvaluationProposal proposal = deliveryEngine.evaluateFrozenPolicy(requested);
        requirePolicyProposalIdentity(proposal, requested);
        RequirementPolicyEvaluationPreparationResult prepared = requirementPolicyTransactionPort.prepareEvaluation(
                proposal, requested, nowEpochMillis);
        if (prepared == null || !requested.equals(prepared.policyEvaluateCommand())
                || !requested.policyRunId().equals(prepared.policyRun().id())) {
            throw new IllegalStateException("policy-evaluate preparation returned a mismatched durable pair: "
                    + requested.commandId());
        }
        RequirementStageCommand durable = stageCommandStore.findById(requested.commandId()).orElseThrow(
                () -> new IllegalStateException("policy-evaluate preparation did not persist its command: "
                        + requested.commandId()));
        if (!durable.equals(requested)) {
            throw new IllegalStateException("policy-evaluate durable command conflicts: " + requested.commandId());
        }
        requirePreparedPolicyEvaluation(durable, task);
        return durable;
    }

    private void requirePreparedPolicyEvaluation(RequirementStageCommand command, RdTask task) {
        RequirementPolicyRunStore policyStore = requirementPolicyRunStore;
        if (policyStore == null) {
            throw new IllegalStateException("policy-evaluate ledger store is not configured");
        }
        RequirementPolicyRun ledger = policyStore.findById(command.policyRunId()).orElseThrow(
                () -> new IllegalStateException("policy-evaluate command has no durable ledger: " + command.commandId()));
        if (!command.commandId().equals(command.policyRunId())
                || ledger.state() != RequirementPolicyRunState.PLAN_READY
                || !ledger.taskId().equals(command.taskId())
                || ledger.sourceTaskVersion() != command.taskVersion()
                || ledger.sourceFencingToken() != command.fencingToken()
                || ledger.boundTaskVersion() != command.taskVersion()
                || ledger.boundFencingToken() != command.fencingToken()
                || task.version() != command.taskVersion()
                || task.fencingToken() != command.fencingToken()) {
            throw new IllegalStateException("policy-evaluate command is not bound to its PLAN_READY ledger: "
                    + command.commandId());
        }
    }

    private void requireDurablePolicyApply(RequirementStageCommand command, RdTask task) {
        RequirementPolicyRunStore policyStore = requirementPolicyRunStore;
        if (policyStore == null) {
            throw new IllegalStateException("policy-apply ledger store is not configured");
        }
        RequirementPolicyRun ledger = policyStore.findById(command.policyRunId()).orElseThrow(
                () -> new IllegalStateException("policy-apply command has no durable policy ledger: "
                        + command.commandId()));
        if (ledger.state() != RequirementPolicyRunState.POLICY_DECIDED
                || !ledger.taskId().equals(command.taskId())
                || ledger.boundTaskVersion() != command.taskVersion()
                || ledger.boundFencingToken() != command.fencingToken()
                || task.version() != command.taskVersion()
                || task.fencingToken() != command.fencingToken()) {
            throw new IllegalStateException("policy-apply command is not the exact recorded evaluation continuation: "
                    + command.commandId());
        }
    }

    private StageTarget nextStageFor(String taskId, RdTaskStatus status) {
        if (status == null) {
            return new StageTarget("REQUIREMENT_DELIVERY", "DELIVERY_WORKFLOW");
        }
        return switch (status) {
            case CREATED -> new StageTarget("REQUIREMENT_DELIVERY", "MATERIAL_COLLECTING");
            case MATERIAL_COLLECTING -> new StageTarget("REQUIREMENT_DELIVERY", "MATERIAL_READY");
            case MATERIAL_READY -> new StageTarget("REQUIREMENT_DELIVERY", "CONTEXT_BUILDING");
            case CONTEXT_BUILDING -> new StageTarget("REQUIREMENT_DELIVERY", "CONTEXT_READY");
            case CONTEXT_READY -> new StageTarget("REQUIREMENT_DELIVERY", "PLAN_GENERATING");
            case PLAN_GENERATING -> new StageTarget("REQUIREMENT_DELIVERY", "PLAN_GENERATED");
            case PLAN_GENERATED -> new StageTarget("REQUIREMENT_DELIVERY", "POLICY_EVALUATE");
            case WAITING_POLICY -> new StageTarget("REQUIREMENT_DELIVERY", "POLICY_APPLY");
            case RECOVERING -> throw new IllegalStateException(
                    "RECOVERING requires an exact durable checkpoint route");
            case WAITING_APPROVAL -> throw new IllegalStateException(
                    "WAITING_APPROVAL may only resume through the durable approval producer");
            case EXECUTING -> nextRoleTarget(taskId);
            case VALIDATING -> new StageTarget("REQUIREMENT_DELIVERY", "DETERMINISTIC_REVIEW");
            case PR_CREATING -> new StageTarget("REQUIREMENT_DELIVERY", "PUBLICATION");
            case COMMITTED -> new StageTarget("REQUIREMENT_DELIVERY", "REPORTING");
            case REPORTING -> new StageTarget("REQUIREMENT_DELIVERY", "COMPLETION");
            default -> new StageTarget("REQUIREMENT_DELIVERY", "DELIVERY_WORKFLOW");
        };
    }

    private StageTarget nextRoleTarget(String taskId) {
        for (AgentRole role : AgentRole.requirementDeliveryOrder()) {
            String stage = "ROLE_EXECUTION:" + role.name();
            RequirementStageCommand existing = stageCommandStore.find(taskId, role.name(), stage).orElse(null);
            if (existing == null || existing.status() != RequirementStageCommand.Status.SUCCEEDED) {
                return new StageTarget(role.name(), stage);
            }
            if (role == AgentRole.CODING_AGENT) {
                RequirementStageCommand hostVerify = stageCommandStore
                        .find(taskId, "REQUIREMENT_DELIVERY", "HOST_VERIFY")
                        .orElse(null);
                if (hostVerify == null || hostVerify.status() != RequirementStageCommand.Status.SUCCEEDED) {
                    return new StageTarget("REQUIREMENT_DELIVERY", "HOST_VERIFY");
                }
            }
        }
        return new StageTarget("REQUIREMENT_DELIVERY", "DETERMINISTIC_REVIEW");
    }

    private void completeStageCommand(RequirementStageCommand stageCommand, RequirementDeliveryResult result) {
        if (stageCommand == null) {
            return;
        }
        try {
            if (result == null || result.status() == RdTaskStatus.FAILED_RETRYABLE) {
                observe(metrics::recordRetry);
                stageCommandStore.fail(stageCommand, workerId,
                        result == null ? "stage executor returned null" : result.errorMessage(),
                        System.currentTimeMillis());
            } else {
                observe(() -> metrics.recordCompleted(stageCommand, System.currentTimeMillis()));
                stageCommandStore.complete(stageCommand, workerId, System.currentTimeMillis());
            }
        } catch (RuntimeException exception) {
            log.warn("requirement stage command outcome sync failed, commandId={}, reason={}",
                    stageCommand.commandId(), safeError(exception));
        }
    }

    private boolean scheduleNextStage(
            RequirementStageCommand completed,
            RequirementDeliveryResult result,
            CompletableFuture<RequirementDeliveryResult> future,
            long nowEpochMillis
    ) {
        if (completed == null || result == null || !stageCanContinue(result.status())) {
            return false;
        }
        RequirementStageCommand next = nextStageCommand(completed, result, nowEpochMillis);
        if (next == null) {
            return false;
        }
        if (next.commandId().equals(completed.commandId())) {
            return false;
        }
        observe(() -> metrics.recordEnqueued(next, nowEpochMillis));
        scheduleStageCommand(next, future, nowEpochMillis);
        return true;
    }

    private RequirementStageCommand nextStageCommand(
            RequirementStageCommand completed,
            RequirementDeliveryResult result,
            long nowEpochMillis
    ) {
        if (completed.stage().startsWith("ROLE_EXECUTION:")) {
            AgentRole currentRole = parseRole(completed.stage());
            if (currentRole == AgentRole.CODING_AGENT) {
                return newCommand(completed.taskId(), completed, "REQUIREMENT_DELIVERY",
                        "HOST_VERIFY", nowEpochMillis);
            }
            List<AgentRole> roles = AgentRole.requirementDeliveryOrder();
            int nextIndex = roles.indexOf(currentRole) + 1;
            if (nextIndex < roles.size()) {
                AgentRole nextRole = roles.get(nextIndex);
                return newCommand(completed.taskId(), completed, nextRole.name(),
                        "ROLE_EXECUTION:" + nextRole.name(), nowEpochMillis);
            }
            return newCommand(completed.taskId(), completed, "REQUIREMENT_DELIVERY",
                    "DETERMINISTIC_REVIEW", nowEpochMillis);
        }
        if ("HOST_VERIFY".equals(completed.stage())) {
            return newCommand(completed.taskId(), completed, AgentRole.QA_AGENT.name(),
                    "ROLE_EXECUTION:" + AgentRole.QA_AGENT.name(), nowEpochMillis);
        }
        String nextStage = switch (completed.stage()) {
            case "DETERMINISTIC_REVIEW" -> "AI_REVIEW";
            case "AI_REVIEW" -> "PUBLICATION";
            case "PUBLICATION" -> "REPORTING";
            case String publicationStage when publicationStage.startsWith("PUBLICATION:") -> "REPORTING";
            case "REPORTING" -> "COMPLETION";
            case "COMPLETION" -> "";
            default -> nextStageFor(result.taskId(), result.status()).stage();
        };
        if (nextStage.isBlank()) {
            return null;
        }
        return newCommand(completed.taskId(), completed, "REQUIREMENT_DELIVERY", nextStage, nowEpochMillis);
    }

    private RequirementStageCommand newCommand(
            String taskId,
            RequirementStageCommand previous,
            String role,
            String stage,
            long nowEpochMillis
    ) {
        if (taskRegistry == null) {
            throw new IllegalStateException("stage continuation requires an authoritative task registry");
        }
        RdTask task = taskRegistry.getTask(taskId);
        if (task == null || task.fencingToken() <= 0L) {
            throw new IllegalStateException("stage continuation requires a positive task fencing token: " + taskId);
        }
        RequirementStageCommand existing = stageCommandStore.find(taskId, role, stage).orElse(null);
        if (existing != null) {
            requireExactTaskIdentity(existing, task, "existing stage continuation");
            if (!previous.policyRunId().equals(existing.policyRunId())) {
                throw new IllegalStateException("existing stage continuation has a different policy authorization");
            }
            return existing;
        }
        long taskVersion = task.version();
        long fencingToken = task.fencingToken();
        String projectId = task instanceof RdRequirementTask requirementTask && !requirementTask.projectId().isBlank()
                ? requirementTask.projectId() : previous.projectId();
        String priority = task.priority();
        return stageCommandFactory.createPendingCommand(
                taskId,
                taskVersion,
                fencingToken,
                role,
                stage,
                projectId,
                priority,
                previous.providerId(),
                previous.policyRunId(),
                nowEpochMillis
        );
    }

    private void requireExactTaskIdentity(RequirementStageCommand command, RdTask task, String subject) {
        if (command == null || task == null
                || command.fencingToken() <= 0L
                || task.fencingToken() <= 0L
                || !command.taskId().equals(task.taskId())
                || command.taskVersion() != task.version()
                || command.fencingToken() != task.fencingToken()) {
            throw new IllegalStateException(subject + " fencing mismatch: "
                    + (command == null ? "" : command.commandId()));
        }
    }

    private void requireExactEnqueueIdentity(
            RequirementStageCommand requested,
            RequirementStageCommand effective,
            String subject
    ) {
        if (effective == null
                || effective.fencingToken() <= 0L
                || !requested.commandId().equals(effective.commandId())
                || !requested.taskId().equals(effective.taskId())
                || requested.taskVersion() != effective.taskVersion()
                || requested.fencingToken() != effective.fencingToken()
                || !requested.role().equals(effective.role())
                || !requested.stage().equals(effective.stage())
                || requested.maxAttempts() != effective.maxAttempts()
                || requested.deadlineEpochMillis() != effective.deadlineEpochMillis()
                || requested.resourceClass() != effective.resourceClass()
                || !requested.resourceRequirements().equals(effective.resourceRequirements())
                || !requested.projectId().equals(effective.projectId())
                || !requested.providerId().equals(effective.providerId())
                || requested.priorityRank() != effective.priorityRank()
                || !requested.policyRunId().equals(effective.policyRunId())) {
            throw new IllegalStateException(subject + " has a different durable identity");
        }
    }

    private String priorityName(int priorityRank) {
        return "P" + Math.max(0, priorityRank);
    }

    private AgentRole parseRole(String stage) {
        String value = stage.substring("ROLE_EXECUTION:".length());
        try {
            return AgentRole.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("unknown role stage: " + stage, exception);
        }
    }

    private record StageTarget(String role, String stage) {
    }

    private boolean stageCanContinue(RdTaskStatus status) {
        return status != null && switch (status) {
            case CREATED, MATERIAL_COLLECTING, MATERIAL_READY, CONTEXT_BUILDING, CONTEXT_READY,
                    PLAN_GENERATING, PLAN_GENERATED, WAITING_POLICY, EXECUTING,
                    VALIDATING, PR_CREATING, COMMITTED, REPORTING, RECOVERING -> true;
            default -> false;
        };
    }

    private RequirementStageCommand failStageCommand(RequirementStageCommand stageCommand, String reason) {
        if (stageCommand == null) {
            return null;
        }
        try {
            observe(metrics::recordRetry);
            RequirementStageCommand failed = stageCommandStore.fail(stageCommand, workerId,
                    reason == null ? "" : reason, ownedNow(stageCommand));
            return failed;
        } catch (RuntimeException exception) {
            log.warn("requirement stage command failure sync failed, commandId={}, reason={}",
                    stageCommand.commandId(), safeError(exception));
            return null;
        }
    }

    private static RdTaskStatus checkpointStatus(
            RequirementStageExecutionPlan plan,
            RequirementDeliveryResult result
    ) {
        if (plan.commandDisposition() == CommandDisposition.RETRYABLE_TECHNICAL_FAILURE) {
            return RdTaskStatus.FAILED_RETRYABLE;
        }
        if (plan.commandDisposition() == CommandDisposition.SUCCEEDED) {
            // Legacy execute bridges may keep mutations empty so memory finalizers without task
            // persistence can still complete the command; treat durable success as COMPLETED for
            // exact checkpoint settlement.
            RdTaskStatus status = result.status();
            return status == RdTaskStatus.FAILED_RETRYABLE
                    || status == RdTaskStatus.FAILED_NEEDS_HUMAN
                    || status == RdTaskStatus.REJECTED
                    || status == RdTaskStatus.CANCELLED
                    || status == RdTaskStatus.DEAD_LETTERED
                    ? status
                    : RdTaskStatus.COMPLETED;
        }
        return result.status();
    }

    /**
     * Retry only the control command. Its task and advisory job remain untouched so a stale or
     * unavailable consumer cannot overwrite the durable WAITING_APPROVAL decision.
     */
    private void failApprovalResumeCommand(RequirementStageCommand stageCommand, String reason) {
        if (stageCommand == null) {
            return;
        }
        try {
            observe(metrics::recordRetry);
            stageCommandStore.fail(stageCommand, workerId,
                    reason == null ? "" : reason, ownedNow(stageCommand));
        } catch (RuntimeException exception) {
            log.warn("approval-resume command retry sync failed, commandId={}, reason={}",
                    stageCommand.commandId(), safeError(exception));
        }
    }

    /**
     * Retries a dedicated policy control command, or atomically exhausts it with POLICY provenance
     * when the technical attempt budget is spent.
     */
    private void failPolicyControlCommand(RequirementStageCommand stageCommand, String reason) {
        if (stageCommand == null) {
            return;
        }
        try {
            observe(metrics::recordRetry);
            if (isTechnicalAttemptExhausted(stageCommand)
                    && ("POLICY_EVALUATE".equals(stageCommand.stage())
                    || "POLICY_APPLY".equals(stageCommand.stage())
                    || "APPROVAL_RESUME".equals(stageCommand.stage()))) {
                exhaustTechnically(
                        stageCommand,
                        reason == null ? "" : reason,
                        RdTaskStatus.FAILED_RETRYABLE,
                        TaskRetryCheckpointStatus.FAILED_RETRYABLE,
                        "POLICY_CONTROL_EXHAUSTED");
                return;
            }
            stageCommandStore.fail(stageCommand, workerId,
                    reason == null ? "" : reason, ownedNow(stageCommand));
        } catch (RuntimeException exception) {
            log.warn("policy control command retry sync failed, commandId={}, reason={}",
                    stageCommand.commandId(), safeError(exception));
        }
    }

    private void persistDeliveryOutcome(RequirementDeliveryJob job, RequirementDeliveryResult result) {
        if (job == null || result == null) {
            return;
        }
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
        if (stageCanContinue(status)) {
            // The stage command owns continuation. Marking the umbrella job complete here avoids
            // a stale job lease while the next durable command is scheduled.
            jobStore.complete(job.jobId(), workerId, System.currentTimeMillis());
            return;
        }
        String reason = result.errorMessage().isBlank()
                ? "requirement delivery ended as " + status
                : result.errorMessage();
        long now = System.currentTimeMillis();
        if (status == RdTaskStatus.FAILED_RETRYABLE) {
            observe(metrics::recordRetry);
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

    private ScheduledFuture<?> scheduleStageHeartbeat(
            RequirementStageCommand stageCommand,
            AtomicBoolean leaseLost
    ) {
        if (heartbeatScheduler == null || stageCommand == null) {
            return null;
        }
        long intervalMillis = Math.max(1_000L, leaseMillis / 3L);
        return heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                stageCommandStore.heartbeat(stageCommand, workerId, System.currentTimeMillis(), leaseMillis);
            } catch (RuntimeException exception) {
                leaseLost.set(true);
                log.warn("requirement stage heartbeat failed, taskId={}, commandId={}, reason={}",
                        stageCommand.taskId(), stageCommand.commandId(), safeError(exception));
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

    /**
     * Atomically terminalizes an exhausted stage command through the host finalization port.
     *
     * <p>Never calls {@code findActiveByTask}; phase is derived from the command stage (and
     * checkpoint lineage when bound) via {@link TaskRetryExhaustionProvenanceFactory}.
     * {@link IllegalStateException} from the atomic boundary always propagates so a CAS/conflict
     * cannot be "compensated" by a provenance-less terminal task write. A narrowed
     * {@link UnsupportedOperationException} fallback exists only for memory/test wiring that
     * genuinely cannot write provenance (no checkpoint/binding exhaustion dependencies); it logs
     * explicitly that no durable provenance was written.
     */
    private void exhaustTechnically(
            RequirementStageCommand stageCommand,
            String reason,
            RdTaskStatus targetTaskStatus,
            TaskRetryCheckpointStatus targetCheckpointStatus,
            String failureKind
    ) {
        if (stageCommand == null) {
            return;
        }
        ExhaustionProvenanceDraft draft = draftExhaustionProvenance(stageCommand, failureKind);
        RdTaskStatus expectedStatus = expectedTaskStatus(stageCommand);
        long now = ownedNow(stageCommand);
        try {
            stageFinalizationPort.exhaustCommand(new RequirementStageFinalizationPort.ExhaustionCommand(
                    stageCommand,
                    workerId,
                    expectedStatus,
                    stageCommand.taskVersion(),
                    stageCommand.fencingToken(),
                    targetTaskStatus,
                    targetCheckpointStatus,
                    draft,
                    reason == null ? "" : reason,
                    resultJsonForExhaustion(draft.failurePhase(), reason),
                    now));
        } catch (UnsupportedOperationException unsupported) {
            if (!allowsProvenanceLessExhaustionFallback()) {
                throw new IllegalStateException(
                        "atomic stage command exhaustion is unavailable while provenance wiring is present; "
                                + "refusing provenance-less fallback: " + stageCommand.commandId(),
                        unsupported);
            }
            log.warn("atomic exhaustion unavailable and no durable provenance wiring; "
                            + "applying stage-derived fallback WITHOUT durable provenance, "
                            + "commandId={}, reason={}",
                    stageCommand.commandId(), safeError(unsupported));
            failStageCommand(stageCommand, reason);
            markTaskAfterTechnicalExhaustion(stageCommand, targetTaskStatus, draft, reason);
            settleExactRetryCheckpoint(stageCommand, targetCheckpointStatus, reason, now);
        }
        failPendingFuture(stageCommand.commandId(), reason);
    }

    /**
     * Whether a provenance-less exhaustion fallback is permissible.
     *
     * <p>Only memory/test dispatchers without checkpoint/binding exhaustion dependencies may fall
     * back. Any production-shaped wiring that can draft checkpoint-bound provenance must fail
     * closed instead of stranding a terminal task without {@code rd_task_failure_provenance}.
     *
     * @return {@code true} only when exhaustion provenance dependencies are absent
     */
    private boolean allowsProvenanceLessExhaustionFallback() {
        return exhaustionProvenanceFactory == null
                && retryCheckpointStore == null
                && attemptBindingStore == null;
    }

    private ExhaustionProvenanceDraft draftExhaustionProvenance(
            RequirementStageCommand stageCommand,
            String failureKind
    ) {
        if (!stageCommand.retryCheckpointId().isBlank()) {
            if (exhaustionProvenanceFactory == null) {
                throw new IllegalStateException(
                        "checkpoint-bound exhaustion requires checkpoint and binding stores: "
                                + stageCommand.commandId());
            }
            return exhaustionProvenanceFactory.draftFor(
                    stageCommand,
                    idGenerator.nextIdString(),
                    failureKind,
                    TaskRetryExhaustionProvenanceFactory.ExecutionContext.empty());
        }
        return new ExhaustionProvenanceDraft(
                idGenerator.nextIdString(),
                canonicalExhaustionFailedStage(stageCommand),
                failurePhaseForCommand(stageCommand),
                "",
                "",
                "",
                stageCommand.policyRunId(),
                resolveExhaustionSourcePlanDigest(stageCommand),
                resolveExhaustionPublicationOperationId(stageCommand),
                failureKind);
    }

    private String canonicalExhaustionFailedStage(RequirementStageCommand stageCommand) {
        TaskFailurePhase phase = failurePhaseForCommand(stageCommand);
        if (phase != TaskFailurePhase.PR_PUBLICATION) {
            return stageCommand.stage();
        }
        String operationId = resolveExhaustionPublicationOperationId(stageCommand);
        return "PUBLICATION:" + operationId;
    }

    private String resolveExhaustionPublicationOperationId(RequirementStageCommand stageCommand) {
        String stage = stageCommand == null ? "" : stageCommand.stage();
        if (!"PUBLICATION".equals(stage) && !stage.startsWith("PUBLICATION:")) {
            return "";
        }
        if (stage.startsWith("PUBLICATION:") && stage.length() > "PUBLICATION:".length()) {
            return stage.substring("PUBLICATION:".length()).strip();
        }
        if (taskRegistry == null) {
            throw new IllegalStateException("publication exhaustion requires task registry: "
                    + stageCommand.commandId());
        }
        RdRequirementTask task = taskRegistry.getRequirementTask(stageCommand.taskId());
        String operationId = publicationOperationId(task);
        if (operationId.isBlank()) {
            throw new IllegalStateException("publication exhaustion requires operation id: "
                    + stageCommand.commandId());
        }
        if (requirementPublicationStore != null
                && requirementPublicationStore.findByOperationId(operationId)
                .filter(publication -> stageCommand.taskId().equals(publication.taskId()))
                .isEmpty()) {
            throw new IllegalStateException("publication exhaustion ledger row is missing: "
                    + stageCommand.commandId());
        }
        return operationId;
    }

    private String resolveExhaustionSourcePlanDigest(RequirementStageCommand stageCommand) {
        if (stageCommand == null || stageCommand.policyRunId().isBlank()
                || failurePhaseForCommand(stageCommand) != TaskFailurePhase.PR_PUBLICATION) {
            return "";
        }
        if (requirementPolicyRunStore == null) {
            throw new IllegalStateException("publication exhaustion requires policy generation digest: "
                    + stageCommand.commandId());
        }
        return requirementPolicyRunStore.findById(stageCommand.policyRunId())
                .map(RequirementPolicyRun::planDigest)
                .filter(digest -> !digest.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "publication exhaustion requires policy generation digest: " + stageCommand.commandId()));
    }

    private static String publicationOperationId(RdRequirementTask task) {
        if (task == null) {
            return "";
        }
        String candidatePatchSha256 = RequirementPublicationIntentFactory.candidatePatchSha256(
                task.executionResultJson());
        if (candidatePatchSha256.isBlank()) {
            return "";
        }
        return RequirementOperationId.of(
                task.taskId(),
                task.baseBranch(),
                task.workBranch().isBlank() ? "requirement/" + task.taskId() : task.workBranch(),
                candidatePatchSha256);
    }

    private void markTaskAfterTechnicalExhaustion(
            RequirementStageCommand stageCommand,
            RdTaskStatus targetTaskStatus,
            ExhaustionProvenanceDraft draft,
            String reason
    ) {
        if (taskRegistry == null || stageCommand == null || draft == null) {
            return;
        }
        try {
            RdTask current = taskRegistry.getTask(stageCommand.taskId());
            if (!(current instanceof RdRequirementTask requirementTask)
                    || requirementTask.status() == RdTaskStatus.FAILED_RETRYABLE
                    || requirementTask.status() == RdTaskStatus.FAILED_NEEDS_HUMAN
                    || requirementTask.status() == RdTaskStatus.REJECTED
                    || requirementTask.status() == RdTaskStatus.CANCELLED
                    || requirementTask.status() == RdTaskStatus.DEAD_LETTERED) {
                return;
            }
            String resultJson = resultJsonForExhaustion(draft.failurePhase(), reason);
            if (targetTaskStatus == RdTaskStatus.DEAD_LETTERED) {
                taskRegistry.transitionRequirementFenced(
                        requirementTask.withConcurrency(
                                stageCommand.taskVersion(), stageCommand.fencingToken()),
                        RdTaskStatus.DEAD_LETTERED,
                        "",
                        resultJson,
                        requirementTask.pullRequestUrl(),
                        reason == null ? "" : reason);
                return;
            }
            taskRegistry.markRequirementFailedRetryable(
                    stageCommand.taskId(), reason == null ? "" : reason, resultJson);
        } catch (RuntimeException stateException) {
            log.warn("failed to persist structured exhaustion failure, taskId={}, reason={}",
                    stageCommand.taskId(), safeError(stateException));
        }
    }

    private void settleExactRetryCheckpoint(
            RequirementStageCommand stageCommand,
            TaskRetryCheckpointStatus targetCheckpointStatus,
            String reason,
            long nowEpochMillis
    ) {
        if (retryCheckpointStore == null
                || stageCommand == null
                || stageCommand.retryCheckpointId().isBlank()) {
            return;
        }
        TaskRetryCheckpoint checkpoint = retryCheckpointStore.find(stageCommand.retryCheckpointId()).orElse(null);
        if (checkpoint == null || checkpoint.status().isTerminal()) {
            return;
        }
        TaskRetryCheckpointStatus target = targetCheckpointStatus == null
                ? TaskRetryCheckpointStatus.FAILED_RETRYABLE : targetCheckpointStatus;
        if (checkpoint.status() == TaskRetryCheckpointStatus.WAITING_APPROVAL
                && target == TaskRetryCheckpointStatus.FAILED_RETRYABLE) {
            target = TaskRetryCheckpointStatus.FAILED_NEEDS_HUMAN;
        }
        try {
            retryCheckpointStore.transition(
                    checkpoint.checkpointId(), checkpoint.status(), target,
                    reason == null ? "" : reason, nowEpochMillis);
        } catch (RuntimeException exception) {
            log.warn("exact retry checkpoint settlement failed, checkpointId={}, reason={}",
                    checkpoint.checkpointId(), safeError(exception));
        }
    }

    /**
     * Marks the task FAILED_RETRYABLE using a stage-derived phase. Never infers phase from
     * {@link RdTaskStatus}.
     */
    private void markTaskRetryableAfterException(RequirementStageCommand stageCommand, Throwable exception) {
        if (taskRegistry == null || stageCommand == null) {
            return;
        }
        try {
            RdTask current = taskRegistry.getTask(stageCommand.taskId());
            if (!(current instanceof RdRequirementTask requirementTask)
                    || requirementTask.status() == RdTaskStatus.FAILED_RETRYABLE
                    || requirementTask.status() == RdTaskStatus.FAILED_NEEDS_HUMAN
                    || requirementTask.status() == RdTaskStatus.REJECTED
                    || requirementTask.status() == RdTaskStatus.CANCELLED
                    || requirementTask.status() == RdTaskStatus.DEAD_LETTERED) {
                return;
            }
            TaskFailurePhase phase = failurePhaseForCommand(stageCommand);
            var result = OBJECT_MAPPER.createObjectNode();
            result.put("failurePhase", phase.name());
            result.put("errorCategory", exception.getClass().getSimpleName());
            result.put("errorMessage", safeError(exception));
            taskRegistry.markRequirementFailedRetryable(
                    stageCommand.taskId(), safeError(exception), result.toString());
        } catch (RuntimeException stateException) {
            log.warn("failed to persist structured requirement failure, taskId={}, reason={}",
                    stageCommand.taskId(), safeError(stateException));
        }
    }

    private static boolean isTechnicalAttemptExhausted(RequirementStageCommand stageCommand) {
        return stageCommand != null && stageCommand.attemptNo() >= stageCommand.maxAttempts();
    }

    private static String resultJsonForExhaustion(TaskFailurePhase phase, String reason) {
        try {
            var result = OBJECT_MAPPER.createObjectNode();
            result.put("failurePhase", phase.name());
            result.put("errorMessage", reason == null ? "" : reason);
            return result.toString();
        } catch (RuntimeException ignored) {
            return "{}";
        }
    }

    /**
     * Derives the failure phase from a durable command stage, never from {@link RdTaskStatus}.
     *
     * @param stageCommand exhausted or failing stage command
     * @return canonical failure phase
     * @throws IllegalStateException when the stage cannot be mapped
     */
    private TaskFailurePhase failurePhaseForCommand(RequirementStageCommand stageCommand) {
        if (stageCommand == null) {
            throw new IllegalStateException("failure phase requires a stage command; task status alone is insufficient");
        }
        TaskFailurePhase sourceLineage = null;
        if (!stageCommand.retryCheckpointId().isBlank() && retryCheckpointStore != null) {
            sourceLineage = retryCheckpointStore.find(stageCommand.retryCheckpointId())
                    .map(TaskRetryCheckpoint::failurePhase)
                    .orElse(null);
        }
        return TaskRetryRoutePlanner.phaseForStage(stageCommand.stage(), sourceLineage);
    }

    /**
     * Returns a CAS clock that still owns the leased command. Focused tests claim with synthetic
     * past timestamps; using wall-clock alone would falsely reject the owned lease.
     */
    private static long ownedNow(RequirementStageCommand stageCommand) {
        long wall = System.currentTimeMillis();
        if (stageCommand != null
                && stageCommand.leaseUntilEpochMillis() > 0L
                && stageCommand.leaseUntilEpochMillis() <= wall) {
            return Math.max(0L, stageCommand.leaseUntilEpochMillis() - 1L);
        }
        return wall;
    }

    /**
     * Bridges execute-only stage adapters (including test lambdas and Mockito defaults that return
     * null from {@code plan}) onto the durable non-mutating plan contract.
     */
    private RequirementStageExecutor bridgeLegacyExecuteOnlyExecutor(RequirementStageExecutor executor) {
        RequirementStageExecutor delegate = Objects.requireNonNull(executor, "stageExecutor must not be null");
        return new RequirementStageExecutor() {
            @Override
            public RequirementStageExecutionPlan plan(RequirementStageCommand command) {
                try {
                    RequirementStageExecutionPlan planned = delegate.plan(command);
                    if (planned != null) {
                        bridgedLegacyResults.remove();
                        return planned;
                    }
                } catch (UnsupportedOperationException unsupported) {
                    // Fall through to the execute bridge for legacy adapters.
                }
                RequirementDeliveryResult executed = delegate.execute(command);
                bridgedLegacyResults.set(executed);
                return planFromLegacyResult(command, executed);
            }

            @Override
            public RequirementDeliveryResult execute(RequirementStageCommand command) {
                return delegate.execute(command);
            }
        };
    }

    private RequirementStageExecutionPlan planFromLegacyResult(
            RequirementStageCommand command,
            RequirementDeliveryResult result
    ) {
        if (result == null) {
            throw new IllegalStateException("stage executor returned no result: "
                    + (command == null ? "null" : command.commandId()));
        }
        RdTaskStatus expected = expectedTaskStatus(command);
        RdTaskStatus target = result.status();
        // Keep mutations empty so memory finalizers without task persistence can still complete
        // the leased command. The bridged execute result is surfaced to the caller future.
        CommandDisposition disposition;
        if (target == RdTaskStatus.FAILED_RETRYABLE) {
            disposition = CommandDisposition.RETRYABLE_TECHNICAL_FAILURE;
        } else if (target == RdTaskStatus.FAILED_NEEDS_HUMAN
                || target == RdTaskStatus.REJECTED
                || target == RdTaskStatus.CANCELLED
                || target == RdTaskStatus.DEAD_LETTERED) {
            disposition = CommandDisposition.TERMINAL_FAILURE;
        } else {
            disposition = CommandDisposition.SUCCEEDED;
        }
        return new RequirementStageExecutionPlan(
                RequirementStageExecutionPlan.CURRENT_SCHEMA_VERSION,
                command.taskId(),
                command.taskVersion(),
                command.fencingToken(),
                expected,
                List.of(),
                disposition,
                ContinuationSpec.terminal(),
                ExternalEffectReceipt.none());
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

    private void markTaskDeadLettered(RequirementStageCommand stageCommand, String reason) {
        if (stageCommand == null) {
            return;
        }
        failPendingFuture(stageCommand.commandId(), reason);
        if (taskRegistry == null) {
            return;
        }
        try {
            RdTask current = taskRegistry.getTask(stageCommand.taskId());
            if (current instanceof RdRequirementTask requirementTask) {
                taskRegistry.transitionRequirementFenced(
                        requirementTask.withConcurrency(
                                stageCommand.taskVersion(), stageCommand.fencingToken()),
                        RdTaskStatus.DEAD_LETTERED,
                        "",
                        requirementTask.executionResultJson(),
                        requirementTask.pullRequestUrl(),
                        reason);
            }
        } catch (RuntimeException exception) {
            log.warn("requirement stage dead-letter task sync failed, taskId={}, reason={}",
                    stageCommand.taskId(), safeError(exception));
        }
    }

    /**
     * A command that reaches a terminal dead-letter state can no longer be claimed by this or a
     * later worker. Complete the local caller rather than leaving it waiting for an impossible
     * recovery claim.
     */
    private void failPendingFuture(String commandId, String reason) {
        if (commandId == null || commandId.isBlank()) {
            return;
        }
        CompletableFuture<RequirementDeliveryResult> future = pendingFutures.remove(commandId);
        if (future != null) {
            future.completeExceptionally(new IllegalStateException(
                    reason == null || reason.isBlank() ? "requirement delivery dead-lettered" : reason));
        }
    }

    /**
     * Returns the live scheduler counters for the Prometheus/controller adapter.
     *
     * @return process-window snapshot
     */
    public RequirementDeliveryMetrics.Snapshot metricsSnapshot() {
        return metrics.snapshot();
    }

    private void observe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.warn("delivery observability recording failed: {}", safeError(exception));
        }
    }

    private String safeError(Throwable exception) {
        String message = exception == null || exception.getMessage() == null
                ? "requirement delivery failed"
                : exception.getMessage();
        return message.length() <= 2_000 ? message : message.substring(0, 2_000);
    }

    /**
     * PostgreSQL and other non-memory modes must use a shared authoritative command store. The
     * in-memory fallback is intentionally limited to an explicit local-memory configuration so a
     * miswired production process cannot silently diverge from its peers.
     */
    private static RequirementStageCommandStore resolveStageCommandStore(
            ObjectProvider<RequirementStageCommandStore> provider,
            String configuredStore
    ) {
        String mode = configuredStore == null ? "" : configuredStore.strip();
        RequirementStageCommandStore store = provider.getIfAvailable();
        if (store == null) {
            if ("memory".equalsIgnoreCase(mode)) {
                return new InMemoryRequirementStageCommandStore();
            }
            throw new IllegalStateException(
                    "requirement delivery requires an authoritative stage command store when "
                            + "rd.knowledge.store=" + (mode.isBlank() ? "<blank>" : mode));
        }
        if (!"memory".equalsIgnoreCase(mode)
                && store instanceof InMemoryRequirementStageCommandStore) {
            throw new IllegalStateException(
                    "requirement delivery cannot use an in-memory stage command store when "
                            + "rd.knowledge.store=" + (mode.isBlank() ? "<blank>" : mode));
        }
        return store;
    }

    /** Converts the bound Spring properties into one immutable policy for this dispatcher. */
    private static RequirementDeliverySchedulingPolicy resolveSchedulingPolicy(
            ObjectProvider<RequirementDeliverySchedulingProperties> provider
    ) {
        RequirementDeliverySchedulingProperties properties = provider == null ? null : provider.getIfAvailable();
        return properties == null ? RequirementDeliverySchedulingPolicy.defaults() : properties.toPolicy();
    }

    private record OptionalDeadLetter(boolean present) {
    }
}
